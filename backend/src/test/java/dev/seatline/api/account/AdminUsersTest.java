package dev.seatline.api.account;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.seatline.api.TestDatabase;
import dev.seatline.api.TestLedger;
import dev.seatline.api.TestUsers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import({TestDatabase.class, TestLedger.class, TestUsers.class})
class AdminUsersTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestUsers testUsers;

    @Test
    void anAdminFindsAnAccountByEmailAndMakesItDoorStaff() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String email = "gate-" + tag + "@example.com";
        String fan = testUsers.bearer(Role.FAN, email);
        testUsers.bearer(Role.FAN, "other-" + tag + "@example.com");
        String admin = testUsers.bearer(Role.ADMIN);

        JsonNode found = json.readTree(mvc.perform(get("/admin/users").param("email", "GATE-" + tag).header("Authorization", admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(found).hasSize(1);
        assertThat(found.get(0).get("email").asText()).isEqualTo(email);
        // "%" and "_" are just characters in the search, not wildcards
        assertThat(json.readTree(mvc.perform(get("/admin/users").param("email", "%" + tag).header("Authorization", admin))
                .andReturn().getResponse().getContentAsString())).isEmpty();

        mvc.perform(put("/admin/users/" + found.get(0).get("id").asText() + "/role").header("Authorization", admin)
                .contentType(MediaType.APPLICATION_JSON).content("{\"role\": \"DOOR_STAFF\"}")).andExpect(status().isNoContent());
        // the old token carried the old role, so it stops working; a new sign-in gets the new one
        mvc.perform(get("/me").header("Authorization", fan)).andExpect(status().isUnauthorized());
    }

    @Test
    void anAdminCantChangeTheirOwnRole() throws Exception {
        String admin = testUsers.bearer(Role.ADMIN);
        String me = json.readTree(mvc.perform(get("/me").header("Authorization", admin)).andReturn().getResponse()
                .getContentAsString()).get("id").asText();
        mvc.perform(put("/admin/users/" + me + "/role").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\": \"FAN\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("cant_change_own_role"));
    }
}
