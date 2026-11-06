package dev.seatline.ledger.server;

import dev.seatline.ledger.client.LedgerClient;
import dev.seatline.ledger.raft.RaftConfig;
import dev.seatline.ledger.rpc.LedgerGrpc;
import dev.seatline.ledger.rpc.RaftGrpc;
import dev.seatline.ledger.rpc.RaftMessage;
import dev.seatline.ledger.rpc.SeatMapRequest;
import dev.seatline.ledger.seats.SeatCommand.CreateEvent;
import dev.seatline.ledger.seats.SeatCommand.Hold;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Three nodes with mutual TLS, using the test-only certificates in src/test/resources/tls. */
@Timeout(60)
class LedgerTlsTest {

    @TempDir
    Path dir;

    final Map<Integer, String> cluster = new TreeMap<>();
    final Map<Integer, String> clients = new TreeMap<>();
    final List<LedgerServer> servers = new ArrayList<>();
    final List<ManagedChannel> channels = new ArrayList<>();

    static Tls cert(String name) {
        try {
            Path tls = Path.of(LedgerTlsTest.class.getResource("/tls/ca.crt").toURI()).getParent();
            return new Tls(tls.resolve(name + ".crt"), tls.resolve(name + ".key"), tls.resolve("ca.crt"));
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    @BeforeEach
    void start() throws IOException {
        for (int id = 1; id <= 3; id++) {
            cluster.put(id, "127.0.0.1:" + freePort());
            clients.put(id, "127.0.0.1:" + freePort());
        }
        for (int id = 1; id <= 3; id++) {
            String c = clients.get(id);
            servers.add(new LedgerServer(new LedgerServer.Config(id, cluster, dir.resolve("n" + id), "127.0.0.1",
                    Integer.parseInt(c.substring(c.lastIndexOf(':') + 1)), false, RaftConfig.DEFAULT, cert("ledger-" + id))));
        }
    }

    @AfterEach
    void stop() {
        channels.forEach(ManagedChannel::shutdownNow);
        servers.forEach(LedgerServer::close);
    }

    @Test
    void theApiWithItsCertificateCanSellSeats() {
        try (LedgerClient api = new LedgerClient(clients, Duration.ofSeconds(15), cert("api"))) {
            // the nodes found each other over TLS and elected a leader, or this write couldn't commit
            assertThat(api.session().write(new CreateEvent("gig", List.of("A1", "A2"), 4)).ok()).isTrue();
            assertThat(api.session().write(new Hold("gig", List.of("A1"), "jo", "h1", 60_000)).ok()).isTrue();
        }
    }

    @Test
    void plainTextIsRefused() {
        ManagedChannel plain = track(NettyChannelBuilder.forTarget(clients.get(1)).usePlaintext().build());
        assertThatThrownBy(() -> LedgerGrpc.newBlockingStub(plain).withDeadlineAfter(5, TimeUnit.SECONDS)
                .seatMap(SeatMapRequest.newBuilder().setEventId("gig").build()))
                .isInstanceOf(StatusRuntimeException.class);
    }

    @Test
    void aCertificateFromAnotherAuthorityIsRefused() throws Exception {
        Path stranger = Path.of(getClass().getResource("/tls/stranger/api.crt").toURI()).getParent();
        // a real-looking "api" certificate, but our nodes' CA didn't sign it
        Tls fake = new Tls(stranger.resolve("api.crt"), stranger.resolve("api.key"), cert("api").ca());
        ManagedChannel ch = track(NettyChannelBuilder.forTarget(clients.get(1)).sslContext(fake.client()).build());
        assertThatThrownBy(() -> LedgerGrpc.newBlockingStub(ch).withDeadlineAfter(5, TimeUnit.SECONDS)
                .seatMap(SeatMapRequest.newBuilder().setEventId("gig").build()))
                .isInstanceOf(StatusRuntimeException.class);
    }

    @Test
    void theApisCertificateCantSpeakRaft() throws Exception {
        ManagedChannel ch = track(NettyChannelBuilder.forTarget(cluster.get(1)).sslContext(cert("api").client()).build());
        CompletableFuture<Status> closed = new CompletableFuture<>();
        StreamObserver<RaftMessage> out = RaftGrpc.newStub(ch).stream(new StreamObserver<>() {
            @Override public void onNext(dev.seatline.ledger.rpc.Empty v) { }
            @Override public void onError(Throwable t) { closed.complete(Status.fromThrowable(t)); }
            @Override public void onCompleted() { closed.complete(Status.OK); }
        });
        out.onNext(RaftMessage.getDefaultInstance());
        assertThat(closed.get(10, TimeUnit.SECONDS).getCode()).isEqualTo(Status.Code.PERMISSION_DENIED);
    }

    @Test
    void aNodesCertificateCantUseTheClientPort() {
        ManagedChannel ch = track(NettyChannelBuilder.forTarget(clients.get(1)).sslContext(cert("ledger-2").client()).build());
        assertThatThrownBy(() -> LedgerGrpc.newBlockingStub(ch).withDeadlineAfter(5, TimeUnit.SECONDS)
                .seatMap(SeatMapRequest.newBuilder().setEventId("gig").build()))
                .isInstanceOfSatisfying(StatusRuntimeException.class,
                        e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.PERMISSION_DENIED));
    }

    ManagedChannel track(ManagedChannel c) {
        channels.add(c);
        return c;
    }

    static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }
}
