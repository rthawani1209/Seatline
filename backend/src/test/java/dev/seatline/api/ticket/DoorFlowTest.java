package dev.seatline.api.ticket;

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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import({TestDatabase.class, TestLedger.class, TestUsers.class})
class DoorFlowTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestUsers testUsers;
    @Autowired TicketCodes codes;

    String organizer;
    String eventId;
    String fan;
    String door;
    String doorEmail;
    List<String> tickets = new ArrayList<>();

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

    /** A fan with two paid tickets, and someone working the door. */
    @BeforeEach
    void ticketsSold() throws Exception {
        organizer = testUsers.bearer(Role.ORGANIZER);
        eventId = newEvent();
        String tier = body(mvc.perform(request(HttpMethod.GET, "/events/" + eventId))).get("tiers").get(0).get("id").asText();

        fan = testUsers.bearer(Role.FAN);
        String holdId = body(call(HttpMethod.POST, "/events/" + eventId + "/holds", fan, """
                {"items": [{"seat": "FLOOR-A-1", "tierId": "%s"}, {"seat": "FLOOR-A-2", "tierId": "%s"}]}"""
                .formatted(tier, tier))).get("holdId").asText();
        body(call(HttpMethod.POST, "/holds/" + holdId + "/checkout", fan, "{\"cardToken\": \"tok_visa\"}"))
                .get("tickets").forEach(t -> tickets.add(t.get("id").asText()));
        doorEmail = "door-" + UUID.randomUUID() + "@example.com";
        door = testUsers.bearer(Role.DOOR_STAFF, doorEmail);
        addToDoorTeam(eventId, doorEmail).andExpect(status().isCreated());
    }

    String newEvent() throws Exception {
        String venue = body(call(HttpMethod.POST, "/venues", organizer, """
                {"name": "Hall", "city": "Davis", "sections": [
                  {"name": "Floor", "rows": [{"label": "A", "seats": 4}]}]}""")).get("id").asText();
        String id = body(call(HttpMethod.POST, "/events", organizer, """
                {"venueId": "%s", "name": "Door Night", "startsAt": "%s", "perFanLimit": 4,
                 "feeBps": 0, "feeFlatCents": 0, "taxBps": 0,
                 "tiers": [{"name": "GA", "kind": "STANDARD", "priceCents": 2000, "sections": ["Floor"]}]}"""
                .formatted(venue, Instant.now().plus(30, ChronoUnit.DAYS)))).get("id").asText();
        call(HttpMethod.POST, "/events/" + id + "/publish", organizer, "{}");
        return id;
    }

    ResultActions addToDoorTeam(String event, String email) throws Exception {
        return call(HttpMethod.POST, "/events/" + event + "/door-staff", organizer, "{\"email\": \"" + email + "\"}");
    }

    String qr(String owner, String ticket) throws Exception {
        return body(call(HttpMethod.GET, "/me/tickets/" + ticket + "/qr", owner, null).andExpect(status().isOk()))
                .get("code").asText();
    }

    ResultActions scanAs(String who, String code, String event) throws Exception {
        String e = event == null ? "" : ", \"eventId\": \"" + event + "\"";
        return call(HttpMethod.POST, "/scan", who, "{\"code\": \"" + code + "\"" + e + "}");
    }

    JsonNode scan(String code, String event) throws Exception {
        return body(scanAs(door, code, event).andExpect(status().isOk()));
    }

    @Test
    void aTicketGetsInOnce() throws Exception {
        String code = qr(fan, tickets.get(0));
        JsonNode first = scan(code, eventId);
        assertThat(first.get("result").asText()).isEqualTo("OK");
        assertThat(first.get("seat").asText()).isEqualTo("FLOOR-A-1");

        JsonNode again = scan(code, eventId);
        assertThat(again.get("result").asText()).isEqualTo("ALREADY_SCANNED");
        assertThat(again.get("scannedAt").asText()).isEqualTo(first.get("scannedAt").asText());
    }

    @Test
    void madeUpOrAlteredCodesAreRefused() throws Exception {
        String code = qr(fan, tickets.get(0));
        String otherTicket = code.replace(tickets.get(0), tickets.get(1)); // same signature, other ticket
        assertThat(scan(otherTicket, eventId).get("result").asText()).isEqualTo("INVALID");
        assertThat(scan("SL1.T-made-up.1.123.AAAAAAAAAAAAAAAAAAAAAA", eventId).get("result").asText()).isEqualTo("INVALID");
        assertThat(scan("hello", eventId).get("result").asText()).isEqualTo("INVALID");
        String otherShow = newEvent();
        addToDoorTeam(otherShow, doorEmail).andExpect(status().isCreated());
        assertThat(scan(code, otherShow).get("result").asText()).isEqualTo("WRONG_EVENT");
    }

    @Test
    void aTransferredTicketsOldCodeStopsWorking() throws Exception {
        String old = qr(fan, tickets.get(1));
        String friendEmail = "friend-" + UUID.randomUUID() + "@example.com";
        String friend = testUsers.bearer(Role.FAN, friendEmail);
        call(HttpMethod.POST, "/tickets/" + tickets.get(1) + "/transfer", fan, "{\"toEmail\": \"" + friendEmail + "\"}")
                .andExpect(status().isNoContent());

        assertThat(scan(old, eventId).get("result").asText()).isEqualTo("OLD_CODE");
        call(HttpMethod.GET, "/me/tickets/" + tickets.get(1) + "/qr", fan, null).andExpect(status().isNotFound());
        assertThat(scan(qr(friend, tickets.get(1)), eventId).get("result").asText()).isEqualTo("OK");
    }

    @Test
    void aScreenshotOfTheCodeGoesStale() throws Exception {
        String ticket = tickets.get(0);
        // the code the app showed 30 seconds ago (two 15-second steps back)
        String old = codes.code(ticket, 1, Instant.now().minusSeconds(30));
        assertThat(scan(old, eventId).get("result").asText()).isEqualTo("EXPIRED");
        // the one before the current step still works, so a code that just rolled over isn't refused
        // (stay clear of a step boundary so the check itself can't roll over mid-test)
        while (Instant.now().getEpochSecond() % 15 >= 13) {
            Thread.sleep(200);
        }
        String justRolled = codes.code(ticket, 1, Instant.now().minusSeconds(15));
        assertThat(scan(justRolled, eventId).get("result").asText()).isEqualTo("OK");
    }

    @Test
    void aReturnedTicketDoesntGetIn() throws Exception {
        String code = qr(fan, tickets.get(0));
        call(HttpMethod.POST, "/tickets/" + tickets.get(0) + "/return", fan, null).andExpect(status().isNoContent());
        assertThat(scan(code, eventId).get("result").asText()).isEqualTo("INVALID");
    }

    @Test
    void twoDoorsScanningTheSameCodeAtOnceLetItInOnce() throws Exception {
        String code = qr(fan, tickets.get(0));
        Callable<String> scanIt = () -> scan(code, eventId).get("result").asText();
        List<String> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Future<String> f : pool.invokeAll(List.of(scanIt, scanIt, scanIt, scanIt))) {
                results.add(f.get());
            }
        }
        assertThat(results).containsOnlyOnce("OK").filteredOn(r -> !r.equals("OK")).containsOnly("ALREADY_SCANNED");
    }

    @Test
    void doorStaffScanOnlyAtTheEventsTheyreOnTheDoorTeamFor() throws Exception {
        String code = qr(fan, tickets.get(0));
        String stranger = testUsers.bearer(Role.DOOR_STAFF);
        scanAs(stranger, code, eventId).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("not_on_door_team"));
        // and a scan has to say which event it's at
        scanAs(door, code, null).andExpect(status().isBadRequest());

        JsonNode mine = body(call(HttpMethod.GET, "/door/events", door, null).andExpect(status().isOk()));
        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).get("id").asText()).isEqualTo(eventId);
        assertThat(body(call(HttpMethod.GET, "/door/events", stranger, null))).isEmpty();

        // only door staff accounts can be added, only by the event's organizer
        String fanEmail = "fan-" + UUID.randomUUID() + "@example.com";
        testUsers.bearer(Role.FAN, fanEmail);
        addToDoorTeam(eventId, fanEmail).andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("not_door_staff"));
        addToDoorTeam(eventId, "nobody-" + UUID.randomUUID() + "@example.com").andExpect(status().isNotFound());
        call(HttpMethod.POST, "/events/" + eventId + "/door-staff", testUsers.bearer(Role.ORGANIZER),
                "{\"email\": \"" + doorEmail + "\"}").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("not_your_event"));

        // taken off the team, they can't scan there any more
        JsonNode team = body(call(HttpMethod.GET, "/events/" + eventId + "/door-staff", organizer, null));
        assertThat(team).hasSize(1);
        call(HttpMethod.DELETE, "/events/" + eventId + "/door-staff/" + team.get(0).get("id").asText(), organizer, null)
                .andExpect(status().isNoContent());
        scanAs(door, code, eventId).andExpect(status().isForbidden());
    }

    @Test
    void aCancelledEventsTicketsAreTurnedAway() throws Exception {
        String code = qr(fan, tickets.get(0));
        call(HttpMethod.POST, "/events/" + eventId + "/cancel", organizer, null).andExpect(status().isNoContent());
        assertThat(scan(code, eventId).get("result").asText()).isEqualTo("CANCELLED");
        // and it's off the door list, and takes no new promo codes
        assertThat(body(call(HttpMethod.GET, "/door/events", door, null)).findValuesAsText("id")).doesNotContain(eventId);
        call(HttpMethod.POST, "/events/" + eventId + "/promo-codes", organizer, """
                {"code": "LATE", "percentOff": 10, "maxUses": 5}""")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("event_cancelled"));
    }

    @Test
    void aScanAndAReturnAtTheSameMomentCantBothWin() throws Exception {
        for (int round = 0; round < 10; round++) {
            String event = newEvent();
            addToDoorTeam(event, doorEmail).andExpect(status().isCreated());
            String tier = body(mvc.perform(request(HttpMethod.GET, "/events/" + event))).get("tiers").get(0).get("id").asText();
            String holdId = body(call(HttpMethod.POST, "/events/" + event + "/holds", fan,
                    "{\"items\": [{\"seat\": \"FLOOR-A-1\", \"tierId\": \"%s\"}]}".formatted(tier))).get("holdId").asText();
            String ticket = body(call(HttpMethod.POST, "/holds/" + holdId + "/checkout", fan, "{\"cardToken\": \"tok_visa\"}"))
                    .get("tickets").get(0).get("id").asText();
            String code = qr(fan, ticket);

            List<Callable<String>> both = List.of(
                    () -> body(scanAs(door, code, event)).get("result").asText(),
                    () -> String.valueOf(call(HttpMethod.POST, "/tickets/" + ticket + "/return", fan, null)
                            .andReturn().getResponse().getStatus()));
            List<String> got = new ArrayList<>();
            try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
                for (Future<String> f : pool.invokeAll(both)) {
                    got.add(f.get());
                }
            }
            boolean scannedIn = got.get(0).equals("OK");
            boolean refunded = got.get(1).equals("204");
            assertThat(scannedIn && refunded).as("round %d: %s", round, got).isFalse();
            assertThat(scannedIn || refunded).as("round %d: %s", round, got).isTrue();
        }
    }
}
