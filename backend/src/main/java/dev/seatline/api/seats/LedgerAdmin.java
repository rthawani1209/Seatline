package dev.seatline.api.seats;

import dev.seatline.api.security.SeatlineSettings;
import dev.seatline.api.web.ApiException;
import dev.seatline.ledger.rpc.AdminGrpc;
import dev.seatline.ledger.rpc.Empty;
import dev.seatline.ledger.rpc.NodeStatus;
import dev.seatline.ledger.rpc.PartitionRequest;
import dev.seatline.ledger.server.LedgerMain;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import jakarta.annotation.PreDestroy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

/**
 * The chaos panel's remote control for the ledger nodes: status, crash, revive, cut off, heal.
 * Only exists when {@code seatline.ledger-admin} lists the nodes' admin addresses (their peer
 * ports, with the nodes started with {@code --admin}); otherwise every call answers 404.
 */
@Component
public class LedgerAdmin {

    public record Node(int id, boolean reachable, boolean up, String role, long term, int leader,
                       long commitIndex, long lastApplied, long lastIndex, List<Integer> cutOff) {}

    private final Map<Integer, ManagedChannel> channels = new TreeMap<>();

    public LedgerAdmin(SeatlineSettings settings) {
        if (settings.ledgerAdmin() == null || settings.ledgerAdmin().isBlank()) {
            return;
        }
        LedgerMain.parseCluster(settings.ledgerAdmin()).forEach((id, address) -> {
            NettyChannelBuilder b = NettyChannelBuilder.forTarget(address);
            channels.put(id, (settings.ledgerTls() == null ? b.usePlaintext()
                    : b.sslContext(settings.ledgerTls().client())).build());
        });
    }

    public boolean enabled() {
        return !channels.isEmpty();
    }

    public List<Node> nodes() {
        List<Node> out = new ArrayList<>();
        channels.forEach((id, ch) -> {
            try {
                NodeStatus s = stub(id).getStatus(Empty.getDefaultInstance());
                out.add(new Node(id, true, s.getUp(), s.getRole(), s.getTerm(), s.getLeader(), s.getCommitIndex(),
                        s.getLastApplied(), s.getLastIndex(), s.getCutOffList()));
            } catch (StatusRuntimeException e) {
                // the whole process is gone, not just "crashed" from the panel
                out.add(new Node(id, false, false, "UNREACHABLE", 0, 0, 0, 0, 0, List.of()));
            }
        });
        return out;
    }

    public void crash(int id) {
        call(() -> stub(id).crash(Empty.getDefaultInstance()));
    }

    public void revive(int id) {
        call(() -> stub(id).revive(Empty.getDefaultInstance()));
    }

    /** Splits one node off from the rest: it drops their messages and they drop its. */
    public void cutOff(int id) {
        stub(id); // 404 for an unknown node before touching any others
        for (int other : channels.keySet()) {
            List<Integer> drop = other == id
                    ? channels.keySet().stream().filter(n -> n != id).toList()
                    : List.of(id);
            call(() -> stub(other).partition(PartitionRequest.newBuilder().addAllCutOff(drop).build()));
        }
    }

    public void healAll() {
        for (int id : channels.keySet()) {
            call(() -> stub(id).heal(Empty.getDefaultInstance()));
        }
    }

    private AdminGrpc.AdminBlockingStub stub(int id) {
        ManagedChannel ch = channels.get(id);
        if (ch == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "no_such_node");
        }
        return AdminGrpc.newBlockingStub(ch).withDeadlineAfter(3, TimeUnit.SECONDS);
    }

    private static void call(Runnable r) {
        try {
            r.run();
        } catch (StatusRuntimeException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "node_unreachable");
        }
    }

    @PreDestroy
    void close() {
        channels.values().forEach(ManagedChannel::shutdownNow);
    }
}
