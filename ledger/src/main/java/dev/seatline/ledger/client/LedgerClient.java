package dev.seatline.ledger.client;

import com.google.protobuf.ByteString;
import dev.seatline.ledger.rpc.LedgerGrpc;
import dev.seatline.ledger.rpc.SeatMapReply;
import dev.seatline.ledger.rpc.SeatMapRequest;
import dev.seatline.ledger.rpc.Status;
import dev.seatline.ledger.rpc.TicketReply;
import dev.seatline.ledger.rpc.TicketRequest;
import dev.seatline.ledger.rpc.WriteReply;
import dev.seatline.ledger.rpc.WriteRequest;
import dev.seatline.ledger.seats.SeatCodec;
import dev.seatline.ledger.seats.SeatCommand;
import dev.seatline.ledger.seats.SeatLedger;
import dev.seatline.ledger.seats.SeatRequest;
import dev.seatline.ledger.seats.SeatResult;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * Talks to the seat ledger cluster: finds the leader, follows "not the leader" hints, and retries
 * through crashes and elections until a deadline.
 *
 * <p>Writes go through a {@link Session}, which owns a random client id and a sequence number. A
 * retried write reuses its sequence number, so if the first attempt did commit (and only the reply
 * was lost), the ledger recognises the retry and returns the original answer instead of applying it
 * twice. A session handles one write at a time; use one session per concurrent caller.
 */
public final class LedgerClient implements AutoCloseable {

    private static final SecureRandom IDS = new SecureRandom();

    private final Map<Integer, ManagedChannel> channels = new TreeMap<>();
    private final Map<Integer, LedgerGrpc.LedgerBlockingStub> stubs = new TreeMap<>();
    private final int[] nodes;
    private final Duration timeout;
    private final Duration callTimeout;
    private volatile int leaderGuess;

    /**
     * @param cluster node id to {@code host:port}
     * @param timeout how long one write or read keeps retrying before giving up
     */
    public LedgerClient(Map<Integer, String> cluster, Duration timeout) {
        cluster.forEach((id, address) -> {
            ManagedChannel channel = NettyChannelBuilder.forTarget(address)
                    .usePlaintext()
                    .maxInboundMessageSize(64 * 1024 * 1024)
                    .build();
            channels.put(id, channel);
            stubs.put(id, LedgerGrpc.newBlockingStub(channel));
        });
        this.nodes = stubs.keySet().stream().mapToInt(Integer::intValue).toArray();
        this.timeout = timeout;
        this.callTimeout = timeout.compareTo(Duration.ofSeconds(2)) < 0 ? timeout : Duration.ofSeconds(2);
        this.leaderGuess = nodes[0];
    }

    public LedgerClient(Map<Integer, String> cluster) {
        this(cluster, Duration.ofSeconds(10));
    }

    /** Thrown when the cluster didn't answer in time. A write may or may not have happened. */
    public static final class UnavailableException extends RuntimeException {
        UnavailableException(String message) {
            super(message);
        }
    }

    /** Opens a session with a fresh random client id. */
    public Session session() {
        long id;
        do {
            id = IDS.nextLong();
        } while (id == 0); // 0 means "don't deduplicate"
        return new Session(id);
    }

    public final class Session {
        private final long clientId;
        // a lock rather than synchronized: on Java 21 a virtual thread blocked inside synchronized
        // pins its carrier thread, which capped the benchmark at one write per CPU core
        private final ReentrantLock lock = new ReentrantLock();
        private long seq;

        private Session(long clientId) {
            this.clientId = clientId;
        }

        public long clientId() {
            return clientId;
        }

        /** Commits a command on the ledger and returns its result. Retries keep the same sequence number. */
        public SeatResult write(SeatCommand command) {
            lock.lock();
            try {
                ByteString request = ByteString.copyFrom(SeatCodec.encode(new SeatRequest(clientId, ++seq, command)));
                WriteRequest proto = WriteRequest.newBuilder().setRequest(request).build();
                WriteReply reply = call(stub -> stub.write(proto), WriteReply::getStatus, WriteReply::getLeaderHint);
                return SeatCodec.decodeResult(reply.getResult().toByteArray());
            } finally {
                lock.unlock();
            }
        }
    }

    /** Every seat of an event, read linearizably through the leader. Null if the event doesn't exist. */
    public List<SeatLedger.SeatView> seatMap(String eventId) {
        SeatMapRequest request = SeatMapRequest.newBuilder().setEventId(eventId).build();
        SeatMapReply reply = call(stub -> stub.seatMap(request), SeatMapReply::getStatus, SeatMapReply::getLeaderHint);
        if (reply.getSeatsCount() == 0) {
            return null;
        }
        List<SeatLedger.SeatView> seats = new ArrayList<>(reply.getSeatsCount());
        for (var s : reply.getSeatsList()) {
            seats.add(new SeatLedger.SeatView(s.getSeatId(), SeatLedger.SeatStatus.valueOf(s.getState().name()),
                    s.getHoldId().isEmpty() ? null : s.getHoldId(),
                    s.getTicketId().isEmpty() ? null : s.getTicketId()));
        }
        return seats;
    }

    public Optional<SeatLedger.Ticket> ticket(String ticketId) {
        TicketRequest request = TicketRequest.newBuilder().setTicketId(ticketId).build();
        TicketReply r = call(stub -> stub.getTicket(request), TicketReply::getStatus, TicketReply::getLeaderHint);
        if (!r.getFound()) {
            return Optional.empty();
        }
        return Optional.of(new SeatLedger.Ticket(r.getTicketId(), r.getEventId(), r.getSeatId(), r.getOwnerFan(),
                r.getVersion(), r.getPaymentRef().isEmpty() ? null : r.getPaymentRef()));
    }

    /** The node the client currently sends requests to. */
    public int leaderGuess() {
        return leaderGuess;
    }

    private <R> R call(Function<LedgerGrpc.LedgerBlockingStub, R> rpc, Function<R, Status> status,
                       Function<R, Integer> hint) {
        long deadline = System.nanoTime() + timeout.toNanos();
        int failures = 0;
        while (true) {
            if (System.nanoTime() > deadline) {
                throw new UnavailableException("no answer from the ledger within " + timeout.toMillis() + " ms");
            }
            int node = leaderGuess;
            int next;
            try {
                R reply = rpc.apply(stubs.get(node).withDeadlineAfter(callTimeout.toMillis(), TimeUnit.MILLISECONDS));
                Status s = status.apply(reply);
                if (s == Status.OK) {
                    return reply;
                }
                if (s == Status.BAD_REQUEST) {
                    throw new IllegalArgumentException("the ledger refused a malformed request");
                }
                int h = hint.apply(reply);
                if (s == Status.NOT_LEADER && h > 0 && h != node && stubs.containsKey(h)) {
                    // a fresh hint is worth following straight away
                    leaderGuess = h;
                    continue;
                }
                next = h > 0 && h != node && stubs.containsKey(h) ? h : after(node);
            } catch (StatusRuntimeException e) {
                next = after(node); // down, unreachable or too slow
            }
            failures++;
            leaderGuess = next;
            if (failures % nodes.length == 0) {
                // tried everyone; give the cluster a moment to elect a leader
                sleep(Math.min(100, 10L * failures / nodes.length) + ThreadLocalRandom.current().nextInt(10));
            }
        }
    }

    private int after(int node) {
        for (int i = 0; i < nodes.length; i++) {
            if (nodes[i] == node) {
                return nodes[(i + 1) % nodes.length];
            }
        }
        return nodes[0];
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UnavailableException("interrupted");
        }
    }

    @Override
    public void close() {
        for (ManagedChannel c : channels.values()) {
            c.shutdownNow();
        }
    }
}
