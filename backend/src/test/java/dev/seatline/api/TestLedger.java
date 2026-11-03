package dev.seatline.api;

import dev.seatline.ledger.raft.RaftConfig;
import dev.seatline.ledger.server.LedgerServer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.util.Map;

/**
 * A real seat ledger for the API tests: one node, in this JVM, with its log in a temp folder. One
 * node is enough here; replication is tested in the ledger module.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestLedger {

    @Bean(destroyMethod = "close")
    LedgerServer ledgerServer() throws IOException {
        int peerPort;
        try (ServerSocket s = new ServerSocket(0)) {
            peerPort = s.getLocalPort();
        }
        return new LedgerServer(new LedgerServer.Config(1, Map.of(1, "127.0.0.1:" + peerPort),
                Files.createTempDirectory("seatline-ledger"), "127.0.0.1", 0, false, RaftConfig.DEFAULT));
    }

    @Bean
    DynamicPropertyRegistrar ledgerAddress(LedgerServer server) {
        return registry -> registry.add("seatline.ledger", () -> "1=127.0.0.1:" + server.clientPort());
    }
}
