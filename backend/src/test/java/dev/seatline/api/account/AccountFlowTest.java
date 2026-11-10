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

@SpringBootTest(properties = "seatline.admin-email=Owner@Example.com")
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

    static String enable(String code) {
        return "{\"code\": \"" + code + "\", \"password\": \"" + PASSWORD + "\"}";
    }

    String token(ResultActions login) throws Exception {
        JsonNode body = json.readTree(login.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        return body.get("token").asText();
    }

    @Test
    void theSiteOwnerBecomesAdminBySettingTheirPasswordFromAResetLink() throws Exception {
        // someone else signs up with the owner's address first, and the confirm link gets clicked
        String squatterPassword = "the squatter's own password";
        register("owner@example.com", squatterPassword).andExpect(status().isCreated());
        postJson("/auth/verify", "{\"token\": \"" + verificationToken("owner@example.com") + "\"}").andExpect(status().isNoContent());
        String squatter = token(login("owner@example.com", squatterPassword, null));
        mvc.perform(get("/me").header("Authorization", "Bearer " + squatter)).andExpect(jsonPath("$.role").value("FAN"));
        // and ties the account to their own phone
        String auth = "Bearer " + squatter;
        String secret = json.readTree(mvc.perform(post("/me/2fa/setup").header("Authorization", auth))
                .andReturn().getResponse().getContentAsString()).get("secret").asText();
        mvc.perform(post("/me/2fa/enable").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\": \"" + Totp.code(secret, Totp.currentStep(Clock.systemUTC())) + "\", \"password\": \""
                        + squatterPassword + "\"}")).andExpect(status().isNoContent());

        // the owner takes it back with a reset link, which only reaches their inbox
        postJson("/auth/forgot", "{\"email\": \"owner@example.com\"}").andExpect(status().isNoContent());
        String body = jdbc.queryForObject("select body from outbox_emails where to_address = ? order by id desc limit 1",
                String.class, "owner@example.com");
        String reset = body.substring(body.indexOf("token=") + 6).split("\\s")[0];
        postJson("/auth/reset", "{\"token\": \"" + reset + "\", \"password\": \"" + PASSWORD + "\"}").andExpect(status().isNoContent());

        mvc.perform(get("/me").header("Authorization", "Bearer " + squatter)).andExpect(status().isUnauthorized());
        login("owner@example.com", squatterPassword, null).andExpect(status().isUnauthorized());
        // the reset cleared the squatter's two-step sign-in, so no code is asked for
        String owner = token(login("owner@example.com", PASSWORD, null));
        mvc.perform(get("/me").header("Authorization", "Bearer " + owner)).andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.twoFactor").value(false));

        // made admin once only: demoted later, another reset doesn't bring the role back
        jdbc.update("update users set role = 'FAN' where email = 'owner@example.com'");
        postJson("/auth/forgot", "{\"email\": \"owner@example.com\"}").andExpect(status().isNoContent());
        String again = jdbc.queryForObject("select body from outbox_emails where to_address = ? order by id desc limit 1",
                String.class, "owner@example.com");
        postJson("/auth/reset", "{\"token\": \"" + again.substring(again.indexOf("token=") + 6).split("\\s")[0]
                + "\", \"password\": \"" + PASSWORD + "\"}").andExpect(status().isNoContent());
        mvc.perform(get("/me").header("Authorization", "Bearer " + token(login("owner@example.com", PASSWORD, null))))
                .andExpect(jsonPath("$.role").value("FAN"));
        // anyone else stays a fan
        String fan = signUp(email());
        mvc.perform(get("/me").header("Authorization", "Bearer " + fan)).andExpect(jsonPath("$.role").value("FAN"));
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
        // the email also links straight to the web app's verify page
        assertThat(jdbc.queryForObject("select body from outbox_emails where to_address = ? order by id desc limit 1",
                String.class, email)).contains("http://localhost:3000/verify?token=" + token);
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
        for (int i = 0; i < LoginLimiter.MAX_PER_ADDRESS_AND_ACCOUNT; i++) {
            login(email, "wrong password!!", null).andExpect(status().isUnauthorized());
        }
        // from this address, even the right password is refused until the window passes
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

        // a right code isn't enough: whoever turns it on has to know the password
        mvc.perform(post("/me/2fa/enable").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\": \"" + Totp.code(secret, step) + "\", \"password\": \"not the password\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("wrong_password"));
        mvc.perform(post("/me/2fa/enable").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content(enable(Totp.code(secret, step)))).andExpect(status().isNoContent());

        login(email, PASSWORD, null).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("code_required"));
        // the code used to enable it can't be used again
        login(email, PASSWORD, Totp.code(secret, step)).andExpect(status().isUnauthorized());
        token(login(email, PASSWORD, Totp.code(secret, step + 1)));
    }

    @Test
    void turningTwoFactorOffNeedsACodeAndKeepsOnlyThisSessionSignedIn() throws Exception {
        String email = email();
        String auth = "Bearer " + signUp(email);
        String secret = json.readTree(mvc.perform(post("/me/2fa/setup").header("Authorization", auth))
                .andReturn().getResponse().getContentAsString()).get("secret").asText();
        long step = Totp.currentStep(Clock.systemUTC());
        mvc.perform(post("/me/2fa/enable").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content(enable(Totp.code(secret, step)))).andExpect(status().isNoContent());

        // the enabling code can't turn it off again, nor can a made-up one
        for (String code : new String[] {Totp.code(secret, step), "12345"}) {
            mvc.perform(post("/me/2fa/disable").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"code\": \"" + code + "\"}")).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("bad_code"));
        }

        String fresh = json.readTree(mvc.perform(post("/me/2fa/disable").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\": \"" + Totp.code(secret, step + 1) + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("token").asText();

        mvc.perform(get("/me").header("Authorization", auth)).andExpect(status().isUnauthorized());
        mvc.perform(get("/me").header("Authorization", "Bearer " + fresh)).andExpect(status().isOk())
                .andExpect(jsonPath("$.twoFactor").value(false));
        token(login(email, PASSWORD, null));
    }

    @Test
    void oneTwoFactorCodeSentTenTimesAtOnceSignsInOnce() throws Exception {
        String email = email();
        String auth = "Bearer " + signUp(email);
        String secret = json.readTree(mvc.perform(post("/me/2fa/setup").header("Authorization", auth))
                .andReturn().getResponse().getContentAsString()).get("secret").asText();
        long step = Totp.currentStep(Clock.systemUTC());
        mvc.perform(post("/me/2fa/enable").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content(enable(Totp.code(secret, step)))).andExpect(status().isNoContent());

        String code = Totp.code(secret, step + 1);
        java.util.List<java.util.concurrent.Callable<Integer>> tries = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            tries.add(() -> login(email, PASSWORD, code).andReturn().getResponse().getStatus());
        }
        java.util.List<Integer> statuses = new java.util.ArrayList<>();
        try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            for (var f : pool.invokeAll(tries)) {
                statuses.add(f.get());
            }
        }
        org.assertj.core.api.Assertions.assertThat(statuses).containsOnlyOnce(200);
    }

    @Test
    void changingThePasswordNeedsTheOldOneAndSignsOutOtherSessions() throws Exception {
        String email = email();
        String old = "Bearer " + signUp(email);
        String other = "Bearer " + token(login(email, PASSWORD, null)); // a second device

        mvc.perform(post("/me/password").header("Authorization", old).contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\": \"not my password\", \"newPassword\": \"a brand new password\"}"))
                .andExpect(status().isForbidden());
        String fresh = "Bearer " + token(mvc.perform(post("/me/password").header("Authorization", old)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\": \"%s\", \"newPassword\": \"a brand new password\"}".formatted(PASSWORD))));

        mvc.perform(get("/me").header("Authorization", other)).andExpect(status().isUnauthorized());
        mvc.perform(get("/me").header("Authorization", fresh)).andExpect(status().isOk());
        login(email, PASSWORD, null).andExpect(status().isUnauthorized());
        token(login(email, "a brand new password", null));
    }

    @Test
    void signingOutEverywhereEndsEverySession() throws Exception {
        String email = email();
        String a = "Bearer " + signUp(email);
        String b = "Bearer " + token(login(email, PASSWORD, null));
        mvc.perform(post("/me/sign-out-everywhere").header("Authorization", a)).andExpect(status().isNoContent());
        mvc.perform(get("/me").header("Authorization", a)).andExpect(status().isUnauthorized());
        mvc.perform(get("/me").header("Authorization", b)).andExpect(status().isUnauthorized());
    }

    @Test
    void aResetLinkSetsANewPasswordOnceAndStrangersLearnNothing() throws Exception {
        String email = email();
        String session = "Bearer " + signUp(email);

        postJson("/auth/forgot", "{\"email\": \"%s\"}".formatted(email)).andExpect(status().isNoContent());
        // an address with no account gets the same answer and no email
        String nobody = email();
        postJson("/auth/forgot", "{\"email\": \"%s\"}".formatted(nobody)).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("select count(*) from outbox_emails where to_address = ?", Integer.class, nobody)).isZero();

        String body = jdbc.queryForObject("select body from outbox_emails where to_address = ? order by id desc limit 1",
                String.class, email);
        String token = body.substring(body.indexOf("token=") + 6, body.indexOf('\n', body.indexOf("token=")));
        String reset = "{\"token\": \"%s\", \"password\": \"after the reset!\"}".formatted(token);
        postJson("/auth/reset", reset).andExpect(status().isNoContent());
        postJson("/auth/reset", reset).andExpect(status().isBadRequest()); // once only

        mvc.perform(get("/me").header("Authorization", session)).andExpect(status().isUnauthorized());
        token(login(email, "after the reset!", null));
    }
}
