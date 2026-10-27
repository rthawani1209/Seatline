package dev.seatline.ledger.server;

import com.sun.net.httpserver.HttpServer;
import dev.seatline.ledger.raft.RaftNode;
import dev.seatline.ledger.raft.RaftObserver;
import dev.seatline.ledger.raft.Role;
import dev.seatline.ledger.rpc.Status;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Node metrics in the Prometheus text format, served at {@code /metrics}. Hand-rolled because it's
 * a dozen numbers; a client library would be more code to configure than this.
 */
public final class Metrics implements RaftObserver {

    // write latency buckets in milliseconds (Prometheus histograms are cumulative)
    private static final double[] BUCKETS_MS = {1, 2, 5, 10, 25, 50, 100, 250, 500, 1000, 5000};

    private final int node;
    private volatile long term;
    private volatile long commitIndex;
    private volatile long lastApplied;
    private volatile int leader;
    private volatile boolean isLeader;
    private volatile boolean up = true;
    private final AtomicLong leaderChanges = new AtomicLong();
    private final AtomicLong elections = new AtomicLong();
    private final AtomicLongArray writes = new AtomicLongArray(Status.values().length);
    private final AtomicLongArray latencyBuckets = new AtomicLongArray(BUCKETS_MS.length);
    private final AtomicLong latencyCount = new AtomicLong();
    private final AtomicLong latencySumMicros = new AtomicLong();
    private HttpServer http;

    Metrics(int node) {
        this.node = node;
    }

    @Override
    public void roleChanged(int node, Role role, long term, int leader) {
        if (role == Role.CANDIDATE) {
            elections.incrementAndGet();
        }
        if (leader > 0 && leader != this.leader) {
            leaderChanges.incrementAndGet();
        }
        this.leader = Math.max(leader, 0);
        this.isLeader = role == Role.LEADER;
        this.term = term;
    }

    void update(RaftNode raft) {
        up = true;
        term = raft.term();
        commitIndex = raft.commitIndex();
        lastApplied = raft.lastApplied();
        isLeader = raft.role() == Role.LEADER;
    }

    void down() {
        up = false;
        isLeader = false;
    }

    void write(Status status, long nanos) {
        writes.incrementAndGet(status.getNumber());
        if (status != Status.OK) {
            return;
        }
        double ms = nanos / 1e6;
        for (int i = 0; i < BUCKETS_MS.length; i++) {
            if (ms <= BUCKETS_MS[i]) {
                latencyBuckets.incrementAndGet(i);
            }
        }
        latencyCount.incrementAndGet();
        latencySumMicros.addAndGet(nanos / 1_000);
    }

    public String render() {
        StringBuilder s = new StringBuilder();
        String n = "{node=\"" + node + "\"}";
        gauge(s, "seatline_node_up", "1 if the node is running", n, up ? 1 : 0);
        gauge(s, "seatline_raft_term", "current Raft term", n, term);
        gauge(s, "seatline_raft_commit_index", "highest log index known to be committed", n, commitIndex);
        gauge(s, "seatline_raft_last_applied", "highest log index applied to the seat ledger", n, lastApplied);
        gauge(s, "seatline_raft_is_leader", "1 if this node is the leader", n, isLeader ? 1 : 0);
        gauge(s, "seatline_raft_leader", "node this one believes is leader, 0 if unknown", n, leader);
        counter(s, "seatline_raft_leader_changes_total", "times this node saw a new leader", n, leaderChanges.get());
        counter(s, "seatline_raft_elections_total", "elections this node started", n, elections.get());

        s.append("# HELP seatline_writes_total client writes handled, by outcome\n");
        s.append("# TYPE seatline_writes_total counter\n");
        for (Status st : Status.values()) {
            if (st != Status.UNRECOGNIZED) {
                s.append("seatline_writes_total{node=\"").append(node).append("\",status=\"")
                        .append(st.name().toLowerCase()).append("\"} ").append(writes.get(st.getNumber())).append('\n');
            }
        }

        s.append("# HELP seatline_write_seconds time from receiving a write to replying, successful writes\n");
        s.append("# TYPE seatline_write_seconds histogram\n");
        for (int i = 0; i < BUCKETS_MS.length; i++) {
            s.append("seatline_write_seconds_bucket{node=\"").append(node).append("\",le=\"")
                    .append(BUCKETS_MS[i] / 1000).append("\"} ").append(latencyBuckets.get(i)).append('\n');
        }
        long count = latencyCount.get();
        s.append("seatline_write_seconds_bucket{node=\"").append(node).append("\",le=\"+Inf\"} ").append(count).append('\n');
        s.append("seatline_write_seconds_sum").append(n).append(' ').append(latencySumMicros.get() / 1e6).append('\n');
        s.append("seatline_write_seconds_count").append(n).append(' ').append(count).append('\n');
        return s.toString();
    }

    private static void gauge(StringBuilder s, String name, String help, String labels, long value) {
        metric(s, name, help, "gauge", labels, value);
    }

    private static void counter(StringBuilder s, String name, String help, String labels, long value) {
        metric(s, name, help, "counter", labels, value);
    }

    private static void metric(StringBuilder s, String name, String help, String type, String labels, long value) {
        s.append("# HELP ").append(name).append(' ').append(help).append('\n');
        s.append("# TYPE ").append(name).append(' ').append(type).append('\n');
        s.append(name).append(labels).append(' ').append(value).append('\n');
    }

    /** Serves {@code /metrics} on the given port (0 picks a free one). Returns the port. */
    public int serve(int port) {
        try {
            http = HttpServer.create(new InetSocketAddress(port), 0);
        } catch (IOException e) {
            throw new IllegalStateException("can't serve metrics on port " + port, e);
        }
        http.createContext("/metrics", exchange -> {
            byte[] body = render().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        http.start();
        return http.getAddress().getPort();
    }

    void stop() {
        if (http != null) {
            http.stop(0);
        }
    }
}
