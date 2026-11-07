package dev.seatline.api.seats;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.seatline.api.TestDatabase;
import dev.seatline.api.TestLedger;
import dev.seatline.api.TestUsers;
import dev.seatline.api.account.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import({TestDatabase.class, TestLedger.class, TestUsers.class})
class ChaosFlowTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestUsers users;

    JsonNode node(String admin) throws Exception {
        String body = mvc.perform(get("/admin/ledger").header("Authorization", admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode nodes = json.readTree(body);
        assertThat(nodes).hasSize(1);
        return nodes.get(0);
    }

    void awaitRole(String admin, String role) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!node(admin).get("role").asText().equals(role)) {
            assertThat(System.nanoTime()).as("node never became " + role).isLessThan(deadline);
            Thread.sleep(50);
        }
    }

    @Test
    void anAdminCanCrashAndReviveALedgerNode() throws Exception {
        String admin = users.bearer(Role.ADMIN);
        awaitRole(admin, "LEADER");
        assertThat(node(admin).get("reachable").asBoolean()).isTrue();

        mvc.perform(post("/admin/ledger/1/crash").header("Authorization", admin)).andExpect(status().isNoContent());
        try {
            JsonNode down = node(admin);
            assertThat(down.get("up").asBoolean()).isFalse();
            assertThat(down.get("role").asText()).isEqualTo("DOWN");
        } finally {
            // the other tests share this ledger, so always bring it back
            mvc.perform(post("/admin/ledger/1/revive").header("Authorization", admin)).andExpect(status().isNoContent());
        }
        awaitRole(admin, "LEADER");
    }

    @Test
    void cuttingOffAndHealingANodeIsRecorded() throws Exception {
        String admin = users.bearer(Role.ADMIN);
        // a one-node cluster has no one to be cut off from, but the call still works and heals
        mvc.perform(post("/admin/ledger/1/cut-off").header("Authorization", admin)).andExpect(status().isNoContent());
        mvc.perform(post("/admin/ledger/heal").header("Authorization", admin)).andExpect(status().isNoContent());
        assertThat(node(admin).get("cutOff")).isEmpty();
    }

    @Test
    void anUnknownNodeIsNotFound() throws Exception {
        mvc.perform(post("/admin/ledger/7/crash").header("Authorization", users.bearer(Role.ADMIN)))
                .andExpect(status().isNotFound());
    }
}
