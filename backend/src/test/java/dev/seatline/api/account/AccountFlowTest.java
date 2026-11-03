package dev.seatline.api.account;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.seatline.api.TestDatabase;
import dev.seatline.api.TestLedger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Clock;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import({TestDatabase.class, TestLedger.class})
class AccountFlowTest {

    static final String PASSWORD = "correct horse battery";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired ObjectMapper json;

    String email() {
        return "fan-" + UUID.randomUUID() + "@example.com";
    }

    ResultActions postJson(String path, String body) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    ResultActions register(String email, String password) throws Exception {
        return postJson("/auth/register", """
                {"email": "%s", "password": "%s", "displayName": "Jordan"}""".formatted(email, password));
    }

    ResultActions login(String email, String password, String code) throws Exception {
        String c = code == null ? "" : ", \"code\": \"" + code + "\"";
        return postJson("/auth/login", """
                {"email": "%s", "password": "%s"%s}""".formatted(email, password, c));
    }

    String verificationToken(String email) {
        String body = jdbc.queryForObject("select body from outbox_emails where to_address = ? order by id desc limit 1",
                String.class, email);
        return body.substring(body.indexOf(": ") + 2, body.indexOf('\n'));
    }

    String signUp(String email) throws Exception {
        register(email, PASSWORD).andExpect(status().isCreated());
        postJson("/auth/verify", "{\"token\": \"" + verificationToken(email) + "\"}").andExpect(status().isNoContent());
        return token(login(email, PASSWORD, null));
    }

    String token(ResultActions login) throws Exception {
        JsonNode body = json.readTree(login.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        return body.get("token").asText();
    }

    @Test
    void registerVerifyLoginAndSeeYourself() throws Exception {
        String email = email();
        String token = signUp(email);
        mvc.perform(get("/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.role").value("FAN"));

        assertThat(users.findByEmail(email).orElseThrow().passwordHash()).startsWith("$argon2id$");
    }

    @Test
    void emailMustBeVerifiedAndTokensWorkOnce() throws Exception {
        String email = email();
        register(email, PASSWORD).andExpect(status().isCreated());
        login(email, PASSWORD, null).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("email_not_verified"));

        String token = verificationToken(email);
        postJson("/auth/verify", "{\"token\": \"" + token + "\"}").andExpect(status().isNoContent());
        postJson("/auth/verify", "{\"token\": \"" + token + "\"}").andExpect(status().isBadRequest());
    }

    @Test
    void sameAnswerForWrongPasswordAndUnknownEmail() throws Exception {
        String email = email();
        signUp(email);
        login(email, "wrong password!!", null).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("bad_credentials"));
        login(email(), "wrong password!!", null).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("bad_credentials"));
    }

    @Test
    void duplicateEmailsAndWeakInputAreRefused() throws Exception {
        String email = email();
        register(email, PASSWORD).andExpect(status().isCreated());
        register(email.toUpperCase(), PASSWORD).andExpect(status().isConflict());
        register(email(), "short").andExpect(status().isBadRequest());
        register("not an email", PASSWORD).andExpect(status().isBadRequest());
    }

    @Test
    void repeatedFailuresAreRateLimited() throws Exception {
        String email = email();
        signUp(email);
        for (int i = 0; i < LoginLimiter.MAX_PER_ACCOUNT; i++) {
            login(email, "wrong password!!", null).andExpect(status().isUnauthorized());
        }
        // even the right password is refused until the window passes
        login(email, PASSWORD, null).andExpect(status().isTooManyRequests());
    }

    @Test
    void twoFactorIsRequiredOnceEnabledAndCodesCantBeReused() throws Exception {
        String email = email();
        String token = signUp(email);
        String auth = "Bearer " + token;

        String body = mvc.perform(post("/me/2fa/setup").header("Authorization", auth))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String secret = json.readTree(body).get("secret").asText();
        long step = Totp.currentStep(Clock.systemUTC());

        mvc.perform(post("/me/2fa/enable").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\": \"" + Totp.code(secret, step) + "\"}")).andExpect(status().isNoContent());

        login(email, PASSWORD, null).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("code_required"));
        // the code used to enable it can't be used again
        login(email, PASSWORD, Totp.code(secret, step)).andExpect(status().isUnauthorized());
        token(login(email, PASSWORD, Totp.code(secret, step + 1)));
    }
}
