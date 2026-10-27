package dev.seatline.ledger.server;

import com.google.protobuf.ByteString;
import dev.seatline.ledger.raft.Completion;
import dev.seatline.ledger.raft.FileStorage;
import dev.seatline.ledger.raft.Message;
import dev.seatline.ledger.raft.RaftConfig;
import dev.seatline.ledger.raft.RaftException;
import dev.seatline.ledger.raft.RaftNode;
import dev.seatline.ledger.raft.Role;
import dev.seatline.ledger.rpc.AdminGrpc;
import dev.seatline.ledger.rpc.Empty;
import dev.seatline.ledger.rpc.LedgerGrpc;
import dev.seatline.ledger.rpc.NodeStatus;
import dev.seatline.ledger.rpc.PartitionRequest;
import dev.seatline.ledger.rpc.RaftGrpc;
import dev.seatline.ledger.rpc.RaftMessage;
import dev.seatline.ledger.rpc.Seat;
import dev.seatline.ledger.rpc.SeatMapReply;
import dev.seatline.ledger.rpc.SeatMapRequest;
import dev.seatline.ledger.rpc.Status;
import dev.seatline.ledger.rpc.TicketReply;
import dev.seatline.ledger.rpc.TicketRequest;
import dev.seatline.ledger.rpc.WriteReply;
import dev.seatline.ledger.rpc.WriteRequest;
import dev.seatline.ledger.seats.SeatLedger;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * One seat ledger node on a real network: the Raft core, its files on disk and three gRPC services
 * (Raft between nodes, Ledger for clients, Admin for the chaos panel and benchmarks).
 *
 * <p>Threading is deliberately simple. Every gRPC call runs on its own virtual thread, and all of
 * them share one lock around the Raft node, which is single-threaded code. A call takes the lock,
 * feeds its input in (a message, a proposal, a tick), and leaves. A single flush thread then takes
 * the lock, fsyncs the log once for everything that arrived since the last flush, and sends the
 * outgoing messages. Under load many proposals share one fsync (group commit) without the Raft code
 * knowing anything about threads.
 *
 * <p>Client replies are also held until after that fsync, so nobody hears "sold" for an entry that
 * isn't on this node's disk yet.
 */
public final class LedgerServer implements AutoCloseable {

    public static final int TICK_MS = 10;
    private static final long CLIENT_WAIT_MS = 5_000;

    public record Config(int id, Map<Integer, String> cluster, Path dataDir, int port, RaftConfig raft) {
        public int[] members() {
            return cluster.keySet().stream().mapToInt(Integer::intValue).sorted().toArray();
        }
    }

    private final Config config;
    private final Metrics metrics;
    private final PeerLinks peers;
    private final Server grpc;
    private final ExecutorService rpcThreads = Executors.newVirtualThreadPerTaskExecutor();
    private final Thread flusher;
    private final Thread ticker;
    private final Semaphore wake = new Semaphore(0);
    private volatile boolean closed;

    private final ReentrantLock lock = new ReentrantLock();
    // guarded by lock
    private boolean up;
    private FileStorage storage;
    private SeatLedger ledger;
    private RaftNode raft;
    private final Set<Integer> cutOff = new TreeSet<>();
    private List<Runnable> replies = new ArrayList<>();

    // calls waiting on a write or read, failed all at once if the node crashes
    private final Set<CompletableFuture<?>> waiting = ConcurrentHashMap.newKeySet();

    public LedgerServer(Config config) {
        this.config = config;
        this.metrics = new Metrics(config.id());
        this.peers = new PeerLinks(config.id(), config.cluster());
        boot();

        this.grpc = NettyServerBuilder.forPort(config.port())
                .executor(rpcThreads)
                .maxInboundMessageSize(PeerLinks.MAX_MESSAGE_BYTES)
                .addService(new RaftService())
                .addService(new LedgerService())
                .addService(new AdminService())
                .build();
        try {
            grpc.start();
        } catch (IOException e) {
            peers.close();
            throw new UncheckedIOException("can't listen on port " + config.port(), e);
        }

        flusher = Thread.ofPlatform().name("ledger-" + config.id() + "-flush").start(this::flushLoop);
        ticker = Thread.ofPlatform().name("ledger-" + config.id() + "-tick").start(this::tickLoop);
    }

    /** Starts (or restarts) the Raft node from whatever is on disk. Caller holds the lock or is the constructor. */
    private void boot() {
        storage = new FileStorage(config.dataDir());
        ledger = new SeatLedger();
        raft = new RaftNode(config.id(), config.members(), config.raft(), storage, ledger,
                new Random(new SecureRandom().nextLong()), System::currentTimeMillis, metrics);
        up = true;
    }

    public int port() {
        return grpc.getPort();
    }

    public Metrics metrics() {
        return metrics;
    }

    // ---------------------------------------------------------------- driving the Raft node

    /** Runs {@code action} against the Raft node under the lock, then wakes the flush thread. */
    private boolean withRaft(Consumer<RaftNode> action) {
        lock.lock();
        try {
            if (!up) {
                return false;
            }
            action.accept(raft);
        } finally {
            lock.unlock();
        }
        wake.release();
        return true;
    }

    private void tickLoop() {
        long next = System.nanoTime();
        while (!closed) {
            withRaft(RaftNode::tick);
            next += TimeUnit.MILLISECONDS.toNanos(TICK_MS);
            long sleep = next - System.nanoTime();
            if (sleep > 0) {
                try {
                    TimeUnit.NANOSECONDS.sleep(sleep);
                } catch (InterruptedException e) {
                    return;
                }
            } else {
                next = System.nanoTime(); // fell behind (GC pause); don't fire a burst of ticks
            }
        }
    }

    private void flushLoop() {
        while (!closed) {
            try {
                wake.acquire();
            } catch (InterruptedException e) {
                return;
            }
            wake.drainPermits();
            flush();
        }
    }

    private void flush() {
        List<Message> out;
        List<Runnable> ready;
        Set<Integer> blocked;
        lock.lock();
        try {
            if (!up) {
                return;
            }
            out = raft.drainMessages(); // fsyncs the log first
            ready = replies;
            replies = new ArrayList<>();
            blocked = cutOff.isEmpty() ? Set.of() : Set.copyOf(cutOff);
            metrics.update(raft);
        } finally {
            lock.unlock();
        }
        for (Runnable r : ready) {
            r.run();
        }
        for (Message m : out) {
            if (blocked.contains(m.to())) {
                peers.dropped.incrementAndGet();
            } else {
                peers.send(m);
            }
        }
    }

    /** Wraps a future so Raft's callback completes it only after the next fsync. Caller holds the lock. */
    private <T> Completion<T> afterSync(CompletableFuture<T> future) {
        return new Completion<>() {
            @Override
            public void complete(T result) {
                replies.add(() -> future.complete(result));
            }

            @Override
            public void fail(RaftException error) {
                replies.add(() -> future.completeExceptionally(error));
            }
        };
    }

    private <T> T await(CompletableFuture<T> future) throws RaftException {
        waiting.add(future);
        try {
            return future.get(CLIENT_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new RaftException.OutcomeUnknown("timed out waiting for the write to commit");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RaftException re) {
                throw re;
            }
            throw new IllegalStateException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RaftException.OutcomeUnknown("interrupted");
        } finally {
            waiting.remove(future);
        }
    }

    private int leaderHint() {
        lock.lock();
        try {
            return up && raft.leader() > 0 ? raft.leader() : 0;
        } finally {
            lock.unlock();
        }
    }

    // ---------------------------------------------------------------- services

    private final class RaftService extends RaftGrpc.RaftImplBase {
        @Override
        public StreamObserver<RaftMessage> stream(StreamObserver<Empty> done) {
            return new StreamObserver<>() {
                @Override
                public void onNext(RaftMessage proto) {
                    Message m = RaftWire.fromProto(proto);
                    withRaft(raft -> {
                        if (!cutOff.contains(m.from())) {
                            raft.step(m);
                        }
                    });
                }

                @Override
                public void onError(Throwable t) {
                    // the peer went away; it will open a new stream when it's back
                }

                @Override
                public void onCompleted() {
                    done.onNext(Empty.getDefaultInstance());
                    done.onCompleted();
                }
            };
        }
    }

    private final class LedgerService extends LedgerGrpc.LedgerImplBase {
        @Override
        public void write(WriteRequest request, StreamObserver<WriteReply> reply) {
            byte[] command = request.getRequest().toByteArray();
            CompletableFuture<byte[]> result = new CompletableFuture<>();
            long start = System.nanoTime();
            WriteReply.Builder out = WriteReply.newBuilder();
            if (!withRaft(raft -> raft.propose(command, afterSync(result)))) {
                out.setStatus(Status.UNAVAILABLE);
            } else {
                try {
                    out.setStatus(Status.OK).setResult(ByteString.copyFrom(await(result)));
                } catch (RaftException e) {
                    out.setStatus(statusOf(e)).setLeaderHint(leaderHint());
                }
            }
            metrics.write(out.getStatus(), System.nanoTime() - start);
            reply.onNext(out.build());
            reply.onCompleted();
        }

        @Override
        public void seatMap(SeatMapRequest request, StreamObserver<SeatMapReply> reply) {
            SeatMapReply.Builder out = SeatMapReply.newBuilder();
            try {
                List<SeatLedger.SeatView> seats = linearizableRead(l -> l.seatMap(request.getEventId(), System.currentTimeMillis()));
                out.setStatus(Status.OK);
                if (seats != null) {
                    for (SeatLedger.SeatView s : seats) {
                        Seat.Builder sb = Seat.newBuilder().setSeatId(s.seatId()).setState(Seat.State.valueOf(s.status().name()));
                        if (s.holdId() != null) {
                            sb.setHoldId(s.holdId());
                        }
                        if (s.ticketId() != null) {
                            sb.setTicketId(s.ticketId());
                        }
                        out.addSeats(sb);
                    }
                }
            } catch (RaftException e) {
                out.setStatus(statusOf(e)).setLeaderHint(leaderHint());
            }
            reply.onNext(out.build());
            reply.onCompleted();
        }

        @Override
        public void getTicket(TicketRequest request, StreamObserver<TicketReply> reply) {
            TicketReply.Builder out = TicketReply.newBuilder();
            try {
                SeatLedger.Ticket t = linearizableRead(l -> l.ticket(request.getTicketId()));
                out.setStatus(Status.OK).setFound(t != null);
                if (t != null) {
                    out.setTicketId(t.ticketId()).setEventId(t.eventId()).setSeatId(t.seatId())
                            .setOwnerFan(t.ownerFan()).setVersion(t.version())
                            .setPaymentRef(t.paymentRef() == null ? "" : t.paymentRef());
                }
            } catch (RaftException e) {
                out.setStatus(statusOf(e)).setLeaderHint(leaderHint());
            }
            reply.onNext(out.build());
            reply.onCompleted();
        }
    }

    /**
     * Waits for ReadIndex to confirm this node is still the leader and has applied everything
     * committed before the read arrived, then runs the query against local state.
     */
    private <T> T linearizableRead(Function<SeatLedger, T> query) {
        CompletableFuture<Void> confirmed = new CompletableFuture<>();
        if (!withRaft(raft -> raft.readIndex(afterSync(confirmed)))) {
            throw new RaftException("node is down");
        }
        await(confirmed);
        lock.lock();
        try {
            if (!up) {
                throw new RaftException("node is down");
            }
            return query.apply(ledger);
        } finally {
            lock.unlock();
        }
    }

    private static Status statusOf(RaftException e) {
        return switch (e) {
            case RaftException.NotLeader n -> Status.NOT_LEADER;
            case RaftException.BadRequest b -> Status.BAD_REQUEST;
            case RaftException.OutcomeUnknown u -> Status.OUTCOME_UNKNOWN;
            default -> Status.UNAVAILABLE;
        };
    }

    private final class AdminService extends AdminGrpc.AdminImplBase {
        @Override
        public void getStatus(Empty request, StreamObserver<NodeStatus> reply) {
            reply.onNext(status());
            reply.onCompleted();
        }

        @Override
        public void crash(Empty request, StreamObserver<Empty> reply) {
            LedgerServer.this.crash();
            done(reply);
        }

        @Override
        public void revive(Empty request, StreamObserver<Empty> reply) {
            LedgerServer.this.revive();
            done(reply);
        }

        @Override
        public void partition(PartitionRequest request, StreamObserver<Empty> reply) {
            lock.lock();
            try {
                cutOff.clear();
                cutOff.addAll(request.getCutOffList());
                cutOff.remove(config.id());
            } finally {
                lock.unlock();
            }
            done(reply);
        }

        @Override
        public void heal(Empty request, StreamObserver<Empty> reply) {
            lock.lock();
            try {
                cutOff.clear();
            } finally {
                lock.unlock();
            }
            done(reply);
        }

        private void done(StreamObserver<Empty> reply) {
            reply.onNext(Empty.getDefaultInstance());
            reply.onCompleted();
        }
    }

    public NodeStatus status() {
        lock.lock();
        try {
            NodeStatus.Builder s = NodeStatus.newBuilder().setId(config.id()).setUp(up).addAllCutOff(cutOff);
            if (up) {
                s.setRole(raft.role().name()).setTerm(raft.term()).setLeader(Math.max(raft.leader(), 0))
                        .setCommitIndex(raft.commitIndex()).setLastApplied(raft.lastApplied())
                        .setLastIndex(raft.lastIndex());
            } else {
                s.setRole("DOWN");
            }
            return s.build();
        } finally {
            lock.unlock();
        }
    }

    /** Stops the node as if the power went out. Only what was fsynced survives. */
    public void crash() {
        lock.lock();
        try {
            if (!up) {
                return;
            }
            up = false;
            storage.losePower();
            storage.close();
            raft = null;
            ledger = null;
            replies.clear();
            metrics.down();
        } finally {
            lock.unlock();
        }
        RaftException lost = new RaftException.OutcomeUnknown("node crashed");
        for (CompletableFuture<?> f : waiting) {
            f.completeExceptionally(lost);
        }
    }

    /** Brings a crashed node back from its files. */
    public void revive() {
        lock.lock();
        try {
            if (!up) {
                boot();
            }
        } finally {
            lock.unlock();
        }
    }

    public boolean isLeader() {
        lock.lock();
        try {
            return up && raft.role() == Role.LEADER;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() {
        closed = true;
        ticker.interrupt();
        flusher.interrupt();
        grpc.shutdownNow();
        try {
            grpc.awaitTermination(2, TimeUnit.SECONDS);
            ticker.join();
            flusher.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        peers.close();
        metrics.stop();
        rpcThreads.shutdownNow();
        lock.lock();
        try {
            if (up) {
                up = false;
                storage.close();
            }
        } finally {
            lock.unlock();
        }
    }
}
