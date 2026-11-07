package dev.seatline.ledger.server;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Ports for test servers. A port from {@code new ServerSocket(0)} comes from the OS's range for
 * outgoing connections, so while one node of a test cluster is starting and dialing its peers,
 * one of those dials can land on a port picked for a node that hasn't bound yet ("Address
 * already in use"). Picking below that range (32768+ on Linux, 49152+ on macOS) avoids it.
 */
final class TestPorts {

    private static final Set<Integer> given = new HashSet<>();

    private TestPorts() {}

    static synchronized int free() throws IOException {
        for (int tries = 0; tries < 200; tries++) {
            int port = ThreadLocalRandom.current().nextInt(20_000, 32_000);
            if (given.contains(port)) {
                continue;
            }
            try (ServerSocket s = new ServerSocket(port, 1, InetAddress.getLoopbackAddress())) {
                given.add(port);
                return port;
            } catch (IOException taken) {
                // try another
            }
        }
        throw new IOException("no free port between 20000 and 32000");
    }
}
