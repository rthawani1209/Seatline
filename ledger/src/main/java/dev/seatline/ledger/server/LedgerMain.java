package dev.seatline.ledger.server;

import dev.seatline.ledger.raft.RaftConfig;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;

/**
 * Runs one ledger node.
 *
 * <pre>
 * java -cp ... dev.seatline.ledger.server.LedgerMain \
 *     --id 1 --cluster 1=localhost:7001,2=localhost:7002,3=localhost:7003 \
 *     --data data/node1 --metrics 9101
 * </pre>
 *
 * Each flag can also come from the environment (SEATLINE_ID, SEATLINE_CLUSTER, SEATLINE_DATA,
 * SEATLINE_METRICS), which is how the Docker image is configured. The node listens on the port of
 * its own entry in the cluster list.
 */
public final class LedgerMain {

    public static void main(String[] args) throws InterruptedException {
        Map<String, String> flags = parseFlags(args);
        int id = Integer.parseInt(setting(flags, "id"));
        Map<Integer, String> cluster = parseCluster(setting(flags, "cluster"));
        Path data = Path.of(setting(flags, "data"));
        int metricsPort = Integer.parseInt(flags.getOrDefault("metrics", env("metrics", "0")));

        String self = cluster.get(id);
        if (self == null) {
            throw new IllegalArgumentException("node " + id + " isn't in the cluster list " + cluster);
        }
        int port = Integer.parseInt(self.substring(self.lastIndexOf(':') + 1));

        LedgerServer server = new LedgerServer(new LedgerServer.Config(id, cluster, data, port, RaftConfig.DEFAULT));
        int served = server.metrics().serve(metricsPort);
        System.out.printf("ledger node %d listening on %d, metrics on %d, data in %s%n", id, server.port(), served, data);

        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            stopped.countDown();
        }));
        stopped.await();
    }

    /** Parses {@code 1=host:port,2=host:port,...}. */
    public static Map<Integer, String> parseCluster(String spec) {
        Map<Integer, String> cluster = new TreeMap<>();
        for (String part : spec.split(",")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length != 2) {
                throw new IllegalArgumentException("bad cluster entry '" + part + "', expected id=host:port");
            }
            cluster.put(Integer.parseInt(kv[0]), kv[1]);
        }
        return cluster;
    }

    private static Map<String, String> parseFlags(String[] args) {
        Map<String, String> flags = new HashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) {
            if (!args[i].startsWith("--")) {
                throw new IllegalArgumentException("expected a --flag, got " + args[i]);
            }
            flags.put(args[i].substring(2), args[i + 1]);
        }
        return flags;
    }

    private static String setting(Map<String, String> flags, String name) {
        String value = flags.getOrDefault(name, env(name, null));
        if (value == null) {
            throw new IllegalArgumentException("missing --" + name + " (or SEATLINE_" + name.toUpperCase() + ")");
        }
        return value;
    }

    private static String env(String name, String fallback) {
        String value = System.getenv("SEATLINE_" + name.toUpperCase());
        return value != null ? value : fallback;
    }
}
