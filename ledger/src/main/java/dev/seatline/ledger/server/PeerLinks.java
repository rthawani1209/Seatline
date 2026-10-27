package dev.seatline.ledger.server;

import dev.seatline.ledger.raft.Message;
import dev.seatline.ledger.rpc.Empty;
import dev.seatline.ledger.rpc.RaftGrpc;
import dev.seatline.ledger.rpc.RaftMessage;
import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.StreamObserver;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Outgoing connections to the other nodes: one channel and one open stream per peer.
 *
 * <p>Sending never blocks and never queues without limit. If a peer is down or its stream is backed
 * up, the message is dropped; Raft already copes with lost messages by resending on the next
 * heartbeat. Only the server's flush thread calls {@link #send}.
 */
final class PeerLinks implements AutoCloseable {

    static final int MAX_MESSAGE_BYTES = 64 * 1024 * 1024;
    // a stream that stays unwritable this long is replaced (see send)
    private static final long STUCK_NANOS = TimeUnit.MILLISECONDS.toNanos(250);

    private final Map<Integer, Link> links = new HashMap<>();
    final AtomicLong sent = new AtomicLong();
    final AtomicLong dropped = new AtomicLong();

    private static final class Link {
        final ManagedChannel channel;
        final RaftGrpc.RaftStub stub;
        volatile ClientCallStreamObserver<RaftMessage> stream;
        long lastWritable;

        Link(String address) {
            channel = NettyChannelBuilder.forTarget(address)
                    .usePlaintext()
                    .maxInboundMessageSize(MAX_MESSAGE_BYTES)
                    .build();
            stub = RaftGrpc.newStub(channel);
        }
    }

    PeerLinks(int self, Map<Integer, String> addresses) {
        addresses.forEach((id, address) -> {
            if (id != self) {
                links.put(id, new Link(address));
            }
        });
    }

    void send(Message m) {
        Link link = links.get(m.to());
        ClientCallStreamObserver<RaftMessage> stream = streamFor(link);
        long now = System.nanoTime();
        if (!stream.isReady()) {
            // Peer is slow or unreachable: drop rather than buffer. A stream opened while the peer
            // was down can sit unwritable even after the peer comes back, so after a while we give
            // up on it and open a fresh one.
            dropped.incrementAndGet();
            if (now - link.lastWritable > STUCK_NANOS) {
                synchronized (link) {
                    link.stream = null;
                }
                stream.cancel("stream not writable", null);
            }
            return;
        }
        link.lastWritable = now;
        stream.onNext(RaftWire.toProto(m));
        sent.incrementAndGet();
    }

    private ClientCallStreamObserver<RaftMessage> streamFor(Link link) {
        ClientCallStreamObserver<RaftMessage> stream = link.stream;
        if (stream != null) {
            return stream;
        }
        AtomicReference<ClientCallStreamObserver<RaftMessage>> mine = new AtomicReference<>();
        StreamObserver<Empty> onClose = new StreamObserver<>() {
            @Override
            public void onNext(Empty value) {}

            @Override
            public void onError(Throwable t) {
                closed();
            }

            @Override
            public void onCompleted() {
                closed();
            }

            private void closed() {
                // reopen on the next send, unless a newer stream has already replaced this one
                synchronized (link) {
                    if (link.stream == mine.get()) {
                        link.stream = null;
                    }
                }
            }
        };
        stream = (ClientCallStreamObserver<RaftMessage>) link.stub.stream(onClose);
        synchronized (link) {
            mine.set(stream);
            link.stream = stream;
        }
        link.lastWritable = System.nanoTime();
        return stream;
    }

    @Override
    public void close() {
        for (Link link : links.values()) {
            link.channel.shutdownNow();
        }
        for (Link link : links.values()) {
            try {
                link.channel.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
