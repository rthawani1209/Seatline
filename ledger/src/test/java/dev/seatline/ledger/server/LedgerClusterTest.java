package dev.seatline.ledger.server;

import com.google.protobuf.ByteString;
import dev.seatline.ledger.client.LedgerClient;
import dev.seatline.ledger.raft.RaftConfig;
import dev.seatline.ledger.rpc.AdminGrpc;
import dev.seatline.ledger.rpc.LedgerGrpc;
import dev.seatline.ledger.rpc.PartitionRequest;
import dev.seatline.ledger.rpc.Status;
import dev.seatline.ledger.rpc.WriteReply;
import dev.seatline.ledger.rpc.WriteRequest;
import dev.seatline.ledger.seats.SeatCodec;
import dev.seatline.ledger.seats.SeatCommand.Confirm;
import dev.seatline.ledger.seats.SeatCommand.CreateEvent;
import dev.seatline.ledger.seats.SeatCommand.Hold;
import dev.seatline.ledger.seats.SeatLedger.SeatStatus;
import dev.seatline.ledger.seats.SeatLedger.SeatView;
import dev.seatline.ledger.seats.SeatRequest;
import dev.seatline.ledger.seats.SeatResult;
import dev.seatline.ledger.seats.SeatResult.Outcome;
import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/** Three real nodes on localhost talking gRPC, with real files on disk. */
@Timeout(60)
class LedgerClusterTest {

    @TempDir
    Path dir;

    final Map<Integer, String> cluster = new TreeMap<>();
    final Map<Integer, LedgerServer> servers = new TreeMap<>();
    final List<ManagedChannel> channels = new ArrayList<>();
    LedgerClient client;

    @BeforeEach
    void start() throws IOException {
        for (int id = 1; id <= 3; id++) {
            cluster.put(id, "127.0.0.1:" + freePort());
        }
        for (int id : cluster.keySet()) {
            servers.put(id, startNode(id));
        }
        client = new LedgerClient(cluster, Duration.ofSeconds(15));
    }

    LedgerServer startNode(int id) {
        String address = cluster.get(id);
        int port = Integer.parseInt(address.substring(address.lastIndexOf(':') + 1));
        return new LedgerServer(new LedgerServer.Config(id, cluster, dir.resolve("node" + id), port, RaftConfig.DEFAULT));
    }

    @AfterEach
    void stop() {
        client.close();
        channels.forEach(ManagedChannel::shutdownNow);
        servers.values().forEach(LedgerServer::close);
    }

    @Test
    void sellsASeatAndEveryNodeAgrees() {
        LedgerClient.Session s = client.session();
        assertThat(s.write(new CreateEvent("gig", List.of("A1", "A2", "A3"), 4)).ok()).isTrue();
        SeatResult hold = s.write(new Hold("gig", List.of("A1", "A2"), "jordan", "h1", 60_000));
        assertThat(hold.ok()).isTrue();
        assertThat(s.write(new Confirm("h1", "pay_1")).ids()).containsExactly("T-h1-A1", "T-h1-A2");

        SeatResult clash = client.session().write(new Hold("gig", List.of("A2"), "sam", "h2", 60_000));
        assertThat(clash.outcome()).isEqualTo(Outcome.SEATS_TAKEN);

        assertThat(client.seatMap("gig")).extracting(SeatView::status)
                .containsExactly(SeatStatus.SOLD, SeatStatus.SOLD, SeatStatus.FREE);
        assertThat(client.ticket("T-h1-A2")).get().extracting(t -> t.ownerFan()).isEqualTo("jordan");
        assertThat(client.seatMap("nope")).isNull();

        long commit = leader().status().getCommitIndex();
        await(() -> servers.values().stream().allMatch(n -> n.status().getLastApplied() >= commit));
    }

    @Test
    void retriedWriteIsAppliedOnce() {
        client.session().write(new CreateEvent("gig", List.of("A1", "A2"), 4));
        int leaderId = leader().status().getId();
        LedgerGrpc.LedgerBlockingStub stub = LedgerGrpc.newBlockingStub(channel(leaderId));

        // same client id and seq twice, as if the first reply had been lost
        byte[] bytes = SeatCodec.encode(new SeatRequest(42, 1, new Hold("gig", List.of("A1"), "jordan", "h1", 60_000)));
        WriteRequest request = WriteRequest.newBuilder().setRequest(ByteString.copyFrom(bytes)).build();
        WriteReply first = stub.write(request);
        WriteReply retry = stub.write(request);

        assertThat(first.getStatus()).isEqualTo(Status.OK);
        assertThat(retry.getResult()).isEqualTo(first.getResult());
        assertThat(client.seatMap("gig")).extracting(SeatView::status).containsExactly(SeatStatus.HELD, SeatStatus.FREE);
    }

    @Test
    void survivesTheLeaderCrashingAndCatchesItUpAfterwards() {
        LedgerClient.Session s = client.session();
        s.write(new CreateEvent("gig", List.of("A1", "A2", "A3"), 4));
        LedgerServer old = leader();
        long oldTerm = old.status().getTerm();
        old.crash();

        // the client finds the new leader by itself
        assertThat(s.write(new Hold("gig", List.of("A3"), "sam", "h1", 60_000)).ok()).isTrue();
        assertThat(leader()).isNotSameAs(old);
        assertThat(leader().status().getTerm()).isGreaterThan(oldTerm);

        old.revive();
        long commit = leader().status().getCommitIndex();
        await(() -> old.status().getLastApplied() >= commit);
    }

    @Test
    void aStoppedNodeRestartsFromDiskAndCatchesUp() {
        LedgerClient.Session s = client.session();
        s.write(new CreateEvent("gig", List.of("A1", "A2", "A3"), 4));
        int victim = leader().status().getId();
        servers.remove(victim).close(); // process gone, port closed

        for (int i = 0; i < 20; i++) {
            s.write(new Hold("gig", List.of("A1"), "jordan", "h" + i, 60_000));
            s.write(new dev.seatline.ledger.seats.SeatCommand.Release("h" + i));
        }
        LedgerServer back = startNode(victim);
        servers.put(victim, back);
        long commit = leader().status().getCommitIndex();
        await(() -> back.status().getLastApplied() >= commit);
    }

    @Test
    void anIsolatedLeaderStepsDownAndTheRestCarryOn() {
        LedgerClient.Session s = client.session();
        s.write(new CreateEvent("gig", List.of("A1", "A2"), 4));
        LedgerServer old = leader();
        int oldId = old.status().getId();
        int[] others = cluster.keySet().stream().mapToInt(Integer::intValue).filter(id -> id != oldId).toArray();

        // cut the leader off through the admin API, from both sides
        admin(oldId).partition(PartitionRequest.newBuilder().addCutOff(others[0]).addCutOff(others[1]).build());
        for (int id : others) {
            admin(id).partition(PartitionRequest.newBuilder().addCutOff(oldId).build());
        }

        assertThat(s.write(new Hold("gig", List.of("A1"), "jordan", "h1", 60_000)).ok()).isTrue();
        await(() -> !old.isLeader()); // check-quorum: it can't hear a majority, so it stops leading

        for (int id : cluster.keySet()) {
            admin(id).heal(dev.seatline.ledger.rpc.Empty.getDefaultInstance());
        }
        long commit = leader().status().getCommitIndex();
        await(() -> old.status().getLastApplied() >= commit);
    }

    @Test
    void exposesPrometheusMetrics() {
        client.session().write(new CreateEvent("gig", List.of("A1"), 4));
        String text = leader().metrics().render();
        assertThat(text).contains("seatline_raft_is_leader{node=\"").contains("seatline_raft_term")
                .contains("seatline_writes_total{node=\"" + leader().status().getId() + "\",status=\"ok\"} 1");
    }

    // ---------------------------------------------------------------- helpers

    LedgerServer leader() {
        LedgerServer[] found = new LedgerServer[1];
        await(() -> {
            for (LedgerServer s : servers.values()) {
                if (s.isLeader()) {
                    found[0] = s;
                    return true;
                }
            }
            return false;
        });
        return found[0];
    }

    ManagedChannel channel(int id) {
        ManagedChannel c = NettyChannelBuilder.forTarget(cluster.get(id)).usePlaintext().build();
        channels.add(c);
        return c;
    }

    AdminGrpc.AdminBlockingStub admin(int id) {
        return AdminGrpc.newBlockingStub(channel(id));
    }

    static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime()).as("condition not met within 10s").isLessThan(deadline);
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        }
    }

    static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }
}
