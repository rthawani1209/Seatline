package dev.seatline.api;

import dev.seatline.ledger.raft.RaftConfig;
import dev.seatline.ledger.server.LedgerServer;
import dev.seatline.ledger.server.Tls;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * A real seat ledger for the API tests: one node, in this JVM, with its log in a temp folder, and
 * mutual TLS on, so every API test also goes through the same certificate checks as a deployment.
 * One node is enough here; replication is tested in the ledger module. Its admin controls are on,
 * for the chaos panel tests.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestLedger {

    private int peerPort;

    @Bean(destroyMethod = "close")
    LedgerServer ledgerServer() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            peerPort = s.getLocalPort();
        }
        return new LedgerServer(new LedgerServer.Config(1, Map.of(1, "127.0.0.1:" + peerPort),
                Files.createTempDirectory("seatline-ledger"), "127.0.0.1", 0, true, RaftConfig.DEFAULT, cert("ledger-1")));
    }

    // the ledger module's test-only certificates (tests run from the backend/ folder)
    static final Path CERTS = Path.of("../ledger/src/test/resources/tls");

    static Tls cert(String name) {
        return new Tls(CERTS.resolve(name + ".crt"), CERTS.resolve(name + ".key"), CERTS.resolve("ca.crt"));
    }

    @Bean
    DynamicPropertyRegistrar ledgerAddress(LedgerServer server) {
        return registry -> {
            registry.add("seatline.ledger", () -> "1=127.0.0.1:" + server.clientPort());
            registry.add("seatline.ledger-admin", () -> "1=127.0.0.1:" + peerPort);
            registry.add("seatline.ledger-tls.cert", () -> CERTS.resolve("api.crt").toString());
            registry.add("seatline.ledger-tls.key", () -> CERTS.resolve("api.key").toString());
            registry.add("seatline.ledger-tls.ca", () -> CERTS.resolve("ca.crt").toString());
        };
    }
}
