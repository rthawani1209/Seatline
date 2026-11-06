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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import({TestDatabase.class, TestLedger.class, TestUsers.class})
class DoorFlowTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestUsers testUsers;
    @Autowired TicketCodes codes;

    String eventId;
    String fan;
    String door;
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
        String organizer = testUsers.bearer(Role.ORGANIZER);
        String venue = body(call(HttpMethod.POST, "/venues", organizer, """
                {"name": "Hall", "city": "Davis", "sections": [
                  {"name": "Floor", "rows": [{"label": "A", "seats": 4}]}]}""")).get("id").asText();
        eventId = body(call(HttpMethod.POST, "/events", organizer, """
                {"venueId": "%s", "name": "Door Night", "startsAt": "%s", "perFanLimit": 4,
                 "feeBps": 0, "feeFlatCents": 0, "taxBps": 0,
                 "tiers": [{"name": "GA", "kind": "STANDARD", "priceCents": 2000, "sections": ["Floor"]}]}"""
                .formatted(venue, Instant.now().plus(30, ChronoUnit.DAYS)))).get("id").asText();
        call(HttpMethod.POST, "/events/" + eventId + "/publish", organizer, "{}");
        String tier = body(mvc.perform(request(HttpMethod.GET, "/events/" + eventId))).get("tiers").get(0).get("id").asText();

        fan = testUsers.bearer(Role.FAN);
        String holdId = body(call(HttpMethod.POST, "/events/" + eventId + "/holds", fan, """
                {"items": [{"seat": "FLOOR-A-1", "tierId": "%s"}, {"seat": "FLOOR-A-2", "tierId": "%s"}]}"""
                .formatted(tier, tier))).get("holdId").asText();
        body(call(HttpMethod.POST, "/holds/" + holdId + "/checkout", fan, "{\"cardToken\": \"tok_visa\"}"))
                .get("tickets").forEach(t -> tickets.add(t.get("id").asText()));
        door = testUsers.bearer(Role.DOOR_STAFF);
    }

    String qr(String owner, String ticket) throws Exception {
        return body(call(HttpMethod.GET, "/me/tickets/" + ticket + "/qr", owner, null).andExpect(status().isOk()))
                .get("code").asText();
    }

    JsonNode scan(String code, String event) throws Exception {
        String e = event == null ? "" : ", \"eventId\": \"" + event + "\"";
        return body(call(HttpMethod.POST, "/scan", door, "{\"code\": \"" + code + "\"" + e + "}").andExpect(status().isOk()));
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
        assertThat(scan(otherTicket, null).get("result").asText()).isEqualTo("INVALID");
        assertThat(scan("SL1.T-made-up.1.123.AAAAAAAAAAAAAAAAAAAAAA", null).get("result").asText()).isEqualTo("INVALID");
        assertThat(scan("hello", null).get("result").asText()).isEqualTo("INVALID");
        assertThat(scan(code, UUID.randomUUID().toString()).get("result").asText()).isEqualTo("WRONG_EVENT");
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
}
