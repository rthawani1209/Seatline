package dev.seatline.ledger.bench;

import dev.seatline.ledger.client.LedgerClient;
import dev.seatline.ledger.rpc.AdminGrpc;
import dev.seatline.ledger.rpc.Empty;
import dev.seatline.ledger.rpc.NodeStatus;
import dev.seatline.ledger.seats.SeatCommand.CreateEvent;
import dev.seatline.ledger.seats.SeatCommand.Hold;
import dev.seatline.ledger.seats.SeatCommand.Release;
import dev.seatline.ledger.seats.SeatResult;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * Benchmarks a real ledger cluster: each node is its own JVM process with its own data directory,
 * fsync on, talking gRPC over localhost. Writes results files to {@code docs/results/}.
 *
 * <ul>
 *   <li><b>Throughput and latency</b>: many clients each hold a seat and release it, over and over.
 *       Every hold and every release is a write committed on a majority, so it counts as one write.
 *   <li><b>Failover</b>: the leader process is killed with SIGKILL while a client keeps writing.
 *       We record how long until another node is leader, and the longest gap the client saw between
 *       two successful writes. Then the killed node is restarted and allowed to catch up before the
 *       next round.
 * </ul>
 *
 * Run it with {@code scripts/bench.sh}.
 */
public final class LedgerBench {

    private final Path work;
    private final String classpath = System.getProperty("java.class.path");
    private final String java = ProcessHandle.current().info().command().orElse("java");
    private final Map<Integer, String> cluster = new TreeMap<>();
    private final Map<Integer, Process> processes = new TreeMap<>();
    private final Map<Integer, ManagedChannel> adminChannels = new TreeMap<>();

    private LedgerBench(Path work) {
        this.work = work;
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> flags = new TreeMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) {
            flags.put(args[i].replaceFirst("^--", ""), args[i + 1]);
        }
        int[] sizes = Arrays.stream(flags.getOrDefault("nodes", "3,5").split(",")).mapToInt(Integer::parseInt).toArray();
        int clients = Integer.parseInt(flags.getOrDefault("clients", "64"));
        int seconds = Integer.parseInt(flags.getOrDefault("seconds", "20"));
        int warmup = Integer.parseInt(flags.getOrDefault("warmup", "5"));
        int failovers = Integer.parseInt(flags.getOrDefault("failovers", "20"));
        Path out = Path.of(flags.getOrDefault("out", "docs/results"));
        String command = "scripts/bench.sh " + String.join(" ", args);

        List<String> throughputRuns = new ArrayList<>();
        List<String> failoverRuns = new ArrayList<>();
        for (int n : sizes) {
            Path work = Files.createTempDirectory("seatline-bench-" + n + "-");
            LedgerBench bench = new LedgerBench(work);
            boolean ok = false;
            try {
                bench.startCluster(n);
                throughputRuns.add(bench.throughput(n, 1, warmup, seconds));
                throughputRuns.add(bench.throughput(n, clients, warmup, seconds));
                if (failovers > 0) {
                    failoverRuns.add(bench.failover(n, failovers));
                }
                ok = true;
            } finally {
                bench.stopCluster(ok);
            }
        }

        Files.createDirectories(out);
        String header = header(command, Files.getFileStore(Path.of(System.getProperty("java.io.tmpdir"))).type());
        write(out.resolve("ledger-throughput.json"), header, throughputRuns);
        if (!failoverRuns.isEmpty()) {
            write(out.resolve("ledger-failover.json"), header, failoverRuns);
        }
    }

    // ---------------------------------------------------------------- cluster

    private void startCluster(int n) throws IOException, InterruptedException {
        for (int id = 1; id <= n; id++) {
            cluster.put(id, "127.0.0.1:" + freePort());
        }
        for (int id : cluster.keySet()) {
            startNode(id);
            adminChannels.put(id, NettyChannelBuilder.forTarget(cluster.get(id)).usePlaintext().build());
        }
        waitFor(() -> leader() > 0, Duration.ofSeconds(30), "no leader elected");
    }

    private void startNode(int id) throws IOException {
        String spec = String.join(",", cluster.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toList());
        ProcessBuilder pb = new ProcessBuilder(java, "-Xmx384m", "-cp", classpath,
                "dev.seatline.ledger.server.LedgerMain",
                "--id", String.valueOf(id), "--cluster", spec, "--data", work.resolve("node" + id).toString());
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(work.resolve("node" + id + ".log").toFile()));
        processes.put(id, pb.start());
    }

    private void stopCluster(boolean deleteFiles) throws InterruptedException {
        adminChannels.values().forEach(ManagedChannel::shutdownNow);
        for (Process p : processes.values()) {
            p.destroy();
        }
        for (Process p : processes.values()) {
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
        }
        if (!deleteFiles) {
            System.out.println("node logs and data kept in " + work);
            return;
        }
        try (Stream<Path> files = Files.walk(work)) {
            files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // temp dir; not worth failing the run over
        }
    }

    private NodeStatus status(int id) {
        try {
            return AdminGrpc.newBlockingStub(adminChannels.get(id))
                    .withDeadlineAfter(200, TimeUnit.MILLISECONDS)
                    .getStatus(Empty.getDefaultInstance());
        } catch (StatusRuntimeException e) {
            return null;
        }
    }

    /** The node that says it's leader in the highest term, or 0. */
    private int leader() {
        int best = 0;
        long bestTerm = -1;
        for (int id : cluster.keySet()) {
            NodeStatus s = status(id);
            if (s != null && s.getRole().equals("LEADER") && s.getTerm() > bestTerm) {
                best = id;
                bestTerm = s.getTerm();
            }
        }
        return best;
    }

    // ---------------------------------------------------------------- throughput

    private String throughput(int n, int clients, int warmupSeconds, int seconds) throws InterruptedException {
        String event = "bench-" + clients;
        int seatsPerClient = 4;
        List<String> seats = new ArrayList<>();
        for (int i = 0; i < clients * seatsPerClient; i++) {
            seats.add("S" + i);
        }
        System.out.printf("%d nodes, %d clients: warming up %ds, measuring %ds%n", n, clients, warmupSeconds, seconds);

        try (LedgerClient client = new LedgerClient(cluster)) {
            client.session().write(new CreateEvent(event, seats, seatsPerClient));
            long measureFrom = System.nanoTime() + TimeUnit.SECONDS.toNanos(warmupSeconds);
            long stopAt = measureFrom + TimeUnit.SECONDS.toNanos(seconds);
            long[][] latencies = new long[clients][];
            int[] counts = new int[clients];
            List<Thread> threads = new ArrayList<>();
            for (int c = 0; c < clients; c++) {
                int me = c;
                threads.add(Thread.ofVirtual().start(() -> {
                    LedgerClient.Session s = client.session();
                    long[] lat = new long[1 << 16];
                    int count = 0;
                    for (int i = 0; System.nanoTime() < stopAt; i++) {
                        String seat = "S" + (me * seatsPerClient + i % seatsPerClient);
                        String holdId = "h" + me + "-" + i;
                        long t0 = System.nanoTime();
                        SeatResult held = s.write(new Hold(event, List.of(seat), "fan" + me, holdId, 60_000));
                        long t1 = System.nanoTime();
                        s.write(new Release(holdId));
                        long t2 = System.nanoTime();
                        if (!held.ok()) {
                            throw new IllegalStateException("hold failed: " + held);
                        }
                        if (t0 >= measureFrom && t2 <= stopAt) {
                            if (count + 2 > lat.length) {
                                lat = Arrays.copyOf(lat, lat.length * 2);
                            }
                            lat[count++] = t1 - t0;
                            lat[count++] = t2 - t1;
                        }
                    }
                    latencies[me] = lat;
                    counts[me] = count;
                }));
            }
            for (Thread t : threads) {
                t.join();
            }

            int total = Arrays.stream(counts).sum();
            long[] all = new long[total];
            int pos = 0;
            for (int c = 0; c < clients; c++) {
                System.arraycopy(latencies[c], 0, all, pos, counts[c]);
                pos += counts[c];
            }
            Arrays.sort(all);
            double perSecond = total / (double) seconds;
            System.out.printf("  %.0f writes/sec, p50 %.2f ms, p99 %.2f ms%n", perSecond, ms(pct(all, 50)), ms(pct(all, 99)));
            return """
                    {"nodes": %d, "concurrent_clients": %d, "measured_seconds": %d, "writes": %d, "writes_per_sec": %.0f, \
                    "latency_ms": {"p50": %.2f, "p90": %.2f, "p99": %.2f, "p999": %.2f, "max": %.2f}}""".formatted(
                    n, clients, seconds, total, perSecond,
                    ms(pct(all, 50)), ms(pct(all, 90)), ms(pct(all, 99)), ms(pct(all, 99.9)), ms(all[all.length - 1]));
        }
    }

    // ---------------------------------------------------------------- failover

    private String failover(int n, int rounds) throws IOException, InterruptedException {
        String event = "failover";
        List<Long> electionMs = new ArrayList<>();
        List<Long> gapMs = new ArrayList<>();
        try (LedgerClient client = new LedgerClient(cluster, Duration.ofSeconds(30))) {
            client.session().write(new CreateEvent(event, List.of("F1"), 1));
            for (int round = 1; round <= rounds; round++) {
                // a client writing nonstop; we record when each write finished
                ConcurrentLinkedQueue<Long> done = new ConcurrentLinkedQueue<>();
                AtomicBoolean running = new AtomicBoolean(true);
                int r = round;
                Thread writer = Thread.ofVirtual().start(() -> {
                    LedgerClient.Session s = client.session();
                    for (int i = 0; running.get(); i++) {
                        String holdId = "f" + r + "-" + i;
                        s.write(new Hold(event, List.of("F1"), "fan", holdId, 60_000));
                        done.add(System.nanoTime());
                        s.write(new Release(holdId));
                        done.add(System.nanoTime());
                    }
                });
                Thread.sleep(500);

                int old = leader();
                long oldTerm = status(old).getTerm();
                long killedAt = System.nanoTime();
                processes.get(old).destroyForcibly(); // SIGKILL: no shutdown hooks, no goodbye
                long[] elected = new long[1];
                waitFor(() -> {
                    for (int id : cluster.keySet()) {
                        if (id == old) {
                            continue;
                        }
                        NodeStatus s = status(id);
                        if (s != null && s.getRole().equals("LEADER") && s.getTerm() > oldTerm) {
                            elected[0] = System.nanoTime();
                            return true;
                        }
                    }
                    return false;
                }, Duration.ofSeconds(10), "no new leader after killing node " + old);

                Thread.sleep(1_000); // let writes resume and settle
                running.set(false);
                writer.join();

                long longestGap = 0;
                long prev = 0;
                for (long t : done) {
                    if (prev != 0 && t > killedAt - TimeUnit.MILLISECONDS.toNanos(100)) {
                        longestGap = Math.max(longestGap, t - prev);
                    }
                    prev = t;
                }
                electionMs.add(TimeUnit.NANOSECONDS.toMillis(elected[0] - killedAt));
                gapMs.add(TimeUnit.NANOSECONDS.toMillis(longestGap));
                System.out.printf("%d nodes, round %d: killed node %d, new leader after %d ms, writes paused %d ms%n",
                        n, round, old, electionMs.getLast(), gapMs.getLast());

                // bring the killed node back and let it catch up before the next round
                processes.get(old).waitFor();
                startNode(old);
                waitFor(() -> {
                    int leader = leader();
                    NodeStatus l = leader > 0 ? status(leader) : null;
                    NodeStatus back = status(old);
                    return l != null && back != null && back.getLastApplied() >= l.getCommitIndex();
                }, Duration.ofSeconds(30), "node " + old + " didn't catch up");
                Thread.sleep(500);
            }
        }
        return """
                {"nodes": %d, "rounds": %d, \
                "new_leader_ms": {"median": %d, "p90": %d, "max": %d, "all": %s}, \
                "client_write_pause_ms": {"median": %d, "p90": %d, "max": %d, "all": %s}}""".formatted(
                n, rounds,
                pctOf(electionMs, 50), pctOf(electionMs, 90), pctOf(electionMs, 100), electionMs,
                pctOf(gapMs, 50), pctOf(gapMs, 90), pctOf(gapMs, 100), gapMs);
    }

    // ---------------------------------------------------------------- helpers

    private static String header(String command, String fsType) throws IOException {
        String cpu = "unknown CPU";
        Path cpuinfo = Path.of("/proc/cpuinfo");
        if (Files.exists(cpuinfo)) {
            cpu = Files.readAllLines(cpuinfo).stream().filter(l -> l.startsWith("model name"))
                    .map(l -> l.substring(l.indexOf(':') + 1).trim()).findFirst().orElse(cpu);
        }
        return """
                  "date": "%s",
                  "machine": "%s, %d cores, %s %s, Java %s",
                  "setup": "every node is its own JVM on the same machine, gRPC over localhost, write-ahead log fsynced to the %s file system before any reply",
                  "command": "%s",""".formatted(LocalDate.now(), cpu, Runtime.getRuntime().availableProcessors(),
                System.getProperty("os.name"), System.getProperty("os.arch"), System.getProperty("java.version"),
                fsType, command);
    }

    private static void write(Path file, String header, List<String> runs) throws IOException {
        String indented = header.lines().map(l -> "  " + l).reduce((a, b) -> a + "\n" + b).orElse("");
        String json = "{\n" + indented + "\n  \"runs\": [\n    " + String.join(",\n    ", runs) + "\n  ]\n}\n";
        Files.writeString(file, json);
        System.out.println("wrote " + file);
    }

    private static long pct(long[] sorted, double p) {
        int i = (int) Math.ceil(p / 100 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, i))];
    }

    private static long pctOf(List<Long> values, double p) {
        return pct(values.stream().mapToLong(Long::longValue).sorted().toArray(), p);
    }

    private static double ms(long nanos) {
        return nanos / 1e6;
    }

    private interface Check {
        boolean ok() throws Exception;
    }

    private static void waitFor(Check check, Duration limit, String failure) {
        long deadline = System.nanoTime() + limit.toNanos();
        try {
            while (!check.ok()) {
                if (System.nanoTime() > deadline) {
                    throw new IllegalStateException(failure);
                }
                Thread.sleep(2);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (Exception e) {
            throw e instanceof RuntimeException re ? re : new IllegalStateException(e);
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    static {
        Locale.setDefault(Locale.ROOT); // decimal points, not commas, in the JSON
    }
}
