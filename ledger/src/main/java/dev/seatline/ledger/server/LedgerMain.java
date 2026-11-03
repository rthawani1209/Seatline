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
 *     --port 8001 --data data/node1 --metrics 9101
 * </pre>
 *
 * <ul>
 *   <li>{@code --cluster}: every node's peer address. This node takes Raft traffic on the port of
 *       its own entry.
 *   <li>{@code --port}: where the API reaches the Ledger service.
 *   <li>{@code --bind}: interface to listen on, default 127.0.0.1. Set it (e.g. 0.0.0.0 in Docker
 *       Compose) only where the network around the nodes is private.
 *   <li>{@code --admin}: also serve the Admin service (crash, partition) on the peer port. Off by
 *       default.
 * </ul>
 *
 * Each flag can also come from the environment (SEATLINE_ID, SEATLINE_CLUSTER, SEATLINE_PORT,
 * SEATLINE_DATA, SEATLINE_BIND, SEATLINE_ADMIN, SEATLINE_METRICS), which is how the Docker image is
 * configured.
 */
public final class LedgerMain {

    public static void main(String[] args) throws InterruptedException {
        Map<String, String> flags = parseFlags(args);
        int id = Integer.parseInt(setting(flags, "id"));
        Map<Integer, String> cluster = parseCluster(setting(flags, "cluster"));
        Path data = Path.of(setting(flags, "data"));
        int clientPort = Integer.parseInt(setting(flags, "port"));
        String bind = flags.getOrDefault("bind", env("bind", "127.0.0.1"));
        boolean admin = Boolean.parseBoolean(flags.getOrDefault("admin", env("admin", "false")));
        int metricsPort = Integer.parseInt(flags.getOrDefault("metrics", env("metrics", "0")));

        LedgerServer server = new LedgerServer(
                new LedgerServer.Config(id, cluster, data, bind, clientPort, admin, RaftConfig.DEFAULT));
        int served = server.metrics().serve(bind, metricsPort);
        System.out.printf("ledger node %d on %s: clients %d, peers %d%s, metrics %d, data in %s%n", id, bind,
                server.clientPort(), server.peerPort(), admin ? " (admin on)" : "", served, data);

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
        for (int i = 0; i < args.length; i++) {
            if (!args[i].startsWith("--")) {
                throw new IllegalArgumentException("expected a --flag, got " + args[i]);
            }
            String name = args[i].substring(2);
            if (name.equals("admin")) {
                flags.put(name, "true"); // a switch, takes no value
            } else if (i + 1 < args.length) {
                flags.put(name, args[++i]);
            } else {
                throw new IllegalArgumentException("--" + name + " needs a value");
            }
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
