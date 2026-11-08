package dev.seatline.api.queue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.seatline.api.TestDatabase;
import dev.seatline.api.TestLedger;
import dev.seatline.api.TestUsers;
import dev.seatline.api.account.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import({TestDatabase.class, TestLedger.class, TestUsers.class})
class QueueFlowTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestUsers testUsers;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;

    String eventId;
    String tier;

    ResultActions call(HttpMethod method, String path, String auth, String body) throws Exception {
        var req = request(method, path).header("Authorization", auth);
        if (body != null) {
            req.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(req);
    }

    JsonNode body(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    /** An on-sale event whose waiting room lets 6 fans in per minute. */
    @BeforeEach
    void busyEvent() throws Exception {
        String organizer = testUsers.bearer(Role.ORGANIZER);
        String venue = body(call(HttpMethod.POST, "/venues", organizer, """
                {"name": "Arena", "city": "Davis", "sections": [
                  {"name": "Floor", "rows": [{"label": "A", "seats": 6}]}]}""")).get("id").asText();
        eventId = body(call(HttpMethod.POST, "/events", organizer, """
                {"venueId": "%s", "name": "Big Show", "startsAt": "%s", "perFanLimit": 4,
                 "feeBps": 0, "feeFlatCents": 0, "taxBps": 0, "queuePerMinute": 6,
                 "tiers": [{"name": "GA", "kind": "STANDARD", "priceCents": 5000, "sections": ["Floor"]}]}"""
                .formatted(venue, Instant.now().plus(30, ChronoUnit.DAYS)))).get("id").asText();
        call(HttpMethod.POST, "/events/" + eventId + "/publish", organizer, "{}").andExpect(status().isNoContent());
        tier = body(mvc.perform(request(HttpMethod.GET, "/events/" + eventId))).get("tiers").get(0).get("id").asText();
        openedSecondsAgo(0); // the sale opens now
    }

    /** Moves the sale's opening time (negative: it opens in the future). */
    void openedSecondsAgo(int seconds) {
        jdbc.update("update events set on_sale_at = now() - make_interval(secs => ?) where id = ?::uuid", seconds, eventId);
    }

    ResultActions hold(String fan, String seat) throws Exception {
        return call(HttpMethod.POST, "/events/" + eventId + "/holds", fan, """
                {"items": [{"seat": "%s", "tierId": "%s"}]}""".formatted(seat, tier));
    }

    JsonNode inLine(String fan) throws Exception {
        return body(call(HttpMethod.GET, "/events/" + eventId + "/queue", fan, null).andExpect(status().isOk()));
    }

    JsonNode join(String fan) throws Exception {
        return body(call(HttpMethod.POST, "/events/" + eventId + "/queue", fan, null).andExpect(status().isOk()));
    }

    @Test
    void fansAreLetInInOrderAtTheEventsRate() throws Exception {
        String a = testUsers.bearer(Role.FAN);
        String b = testUsers.bearer(Role.FAN);
        String c = testUsers.bearer(Role.FAN);
        join(a);
        join(b);
        join(c);
        // joining twice keeps your place
        JsonNode third = join(c);
        assertThat(third.get("position").asLong()).isEqualTo(3);
        assertThat(third.get("estimatedWaitSeconds").asLong()).isEqualTo(30); // 3 fans at 6 a minute
        hold(a, "FLOOR-A-1").andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("wait_in_queue"));

        openedSecondsAgo(25); // 25 s at 6 a minute: two fans let in
        assertThat(inLine(a).get("admitted").asBoolean()).isTrue();
        assertThat(inLine(b).get("admitted").asBoolean()).isTrue();
        JsonNode stillWaiting = inLine(c);
        assertThat(stillWaiting.get("admitted").asBoolean()).isFalse();
        assertThat(stillWaiting.get("position").asLong()).isEqualTo(1);

        hold(a, "FLOOR-A-1").andExpect(status().isCreated());
        hold(c, "FLOOR-A-2").andExpect(status().isForbidden());
    }

    @Test
    void turnsNobodyUsedArentSavedUp() throws Exception {
        openedSecondsAgo(1800); // half an hour at 6 a minute, and nobody came
        List<Long> places = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            JsonNode s = join(testUsers.bearer(Role.FAN));
            assertThat(s.get("admitted").asBoolean()).isFalse();
            places.add(s.get("position").asLong());
        }
        // the crowd goes in at the rate, one every 10 s, not all at once
        assertThat(places).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L);
    }

    @Test
    void arrivingBeforeTheSaleOpensDoesntMakeYouFirst() throws Exception {
        openedSecondsAgo(-600); // opens in 10 minutes
        List<String> early = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            String fan = testUsers.bearer(Role.FAN);
            early.add(fan);
            JsonNode s = join(fan);
            assertThat(s.get("position").asLong()).isZero();
            assertThat(s.get("opensAt").isNull()).isFalse();
        }
        hold(early.getFirst(), "FLOOR-A-1").andExpect(status().isForbidden());

        openedSecondsAgo(0);
        List<Long> places = new ArrayList<>();
        for (String fan : early) {
            places.add(inLine(fan).get("position").asLong());
        }
        // everyone who came early is in line exactly once, in a shuffled order
        assertThat(places).containsExactlyInAnyOrderElementsOf(LongStream.rangeClosed(1, 20).boxed().toList());
        assertThat(places).isNotEqualTo(LongStream.rangeClosed(1, 20).boxed().toList());
        // and someone arriving after the opening goes behind them
        assertThat(join(testUsers.bearer(Role.FAN)).get("position").asLong()).isEqualTo(21);
    }

    @Test
    void someoneWhoNeverJoinedIsNotInTheLine() throws Exception {
        call(HttpMethod.GET, "/events/" + eventId + "/queue", testUsers.bearer(Role.FAN), null)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("not_in_queue"));
    }

    @Test
    void lettingAHoldRunOutMeansAShortWaitButReleasingDoesNot() throws Exception {
        String fan = testUsers.bearer(Role.FAN);
        join(fan);
        openedSecondsAgo(60); // plenty of turns since this fan joined
        assertThat(inLine(fan).get("admitted").asBoolean()).isTrue();

        String released = body(hold(fan, "FLOOR-A-3").andExpect(status().isCreated())).get("holdId").asText();
        call(HttpMethod.DELETE, "/holds/" + released, fan, null).andExpect(status().isNoContent());
        String ranOut = body(hold(fan, "FLOOR-A-3").andExpect(status().isCreated())).get("holdId").asText();

        // as if the 10 minutes had passed a moment ago without paying
        jdbc.update("update holds set expires_at = now() - interval '30 seconds' where id = ?", ranOut);
        hold(fan, "FLOOR-A-4").andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.error").value("cooldown"));
        jdbc.update("update holds set expires_at = now() - interval '3 minutes' where id = ?", ranOut);
        hold(fan, "FLOOR-A-4").andExpect(status().isCreated());
    }

    @Test
    void lettingGoAtTheLastMinuteWaitsLikeRunningOut() throws Exception {
        String fan = testUsers.bearer(Role.FAN);
        join(fan);
        openedSecondsAgo(60);
        assertThat(inLine(fan).get("admitted").asBoolean()).isTrue();
        String late = body(hold(fan, "FLOOR-A-3").andExpect(status().isCreated())).get("holdId").asText();
        // as if the fan had sat on the seats for eight of the ten minutes
        jdbc.update("update holds set expires_at = now() + interval '2 minutes' where id = ?", late);
        call(HttpMethod.DELETE, "/holds/" + late, fan, null).andExpect(status().isNoContent());
        hold(fan, "FLOOR-A-4").andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.error").value("cooldown"));
    }

    @Test
    void joiningAgainWhileLetInKeepsThePassAndDoesntTakeASecondPlace() throws Exception {
        String fan = testUsers.bearer(Role.FAN);
        join(fan);
        openedSecondsAgo(60);
        JsonNode in = inLine(fan);
        assertThat(in.get("admitted").asBoolean()).isTrue();

        long waitingBefore = waiting();
        JsonNode again = join(fan);
        assertThat(again.get("admitted").asBoolean()).isTrue();
        assertThat(again.get("passExpiresAt").asText()).isNotBlank();
        assertThat(waiting()).isEqualTo(waitingBefore);
    }

    /** Everyone with a number in the line (let-in fans are taken out of it). */
    long waiting() {
        return redis.opsForZSet().zCard("queue:" + eventId);
    }

    @Test
    void oneAddressCanOnlyBringSoManyAccountsIntoTheLine() throws Exception {
        String crowd = "203.0.113." + (int) (Math.random() * 250 + 1);
        for (int i = 0; i < WaitingRoom.MAX_FANS_PER_ADDRESS; i++) {
            joinFrom(testUsers.bearer(Role.FAN), crowd).andExpect(status().isOk());
        }
        joinFrom(testUsers.bearer(Role.FAN), crowd).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("too_many_attempts"));
        // someone already in line can still check in again, and other addresses aren't affected
        String inLine = testUsers.bearer(Role.FAN);
        joinFrom(inLine, "198.51.100.7").andExpect(status().isOk());
        joinFrom(inLine, "198.51.100.7").andExpect(status().isOk());
    }

    ResultActions joinFrom(String fan, String address) throws Exception {
        return mvc.perform(request(HttpMethod.POST, "/events/" + eventId + "/queue").header("Authorization", fan)
                .with(r -> {
                    r.setRemoteAddr(address);
                    return r;
                }));
    }
}
