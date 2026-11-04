package dev.seatline.api.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.seatline.api.TestDatabase;
import dev.seatline.api.TestLedger;
import dev.seatline.api.TestUsers;
import dev.seatline.api.account.Role;
import dev.seatline.api.seats.Seats;
import dev.seatline.ledger.seats.SeatCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import({TestDatabase.class, TestLedger.class, TestUsers.class})
class EventFlowTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestUsers testUsers;
    @Autowired Seats ledger;

    String organizer;
    String venueId;

    ResultActions post(String path, String auth, String body) throws Exception {
        var req = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                .contentType(MediaType.APPLICATION_JSON).content(body);
        if (auth != null) {
            req.header("Authorization", auth);
        }
        return mvc.perform(req);
    }

    JsonNode body(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    @BeforeEach
    void venue() throws Exception {
        organizer = testUsers.bearer(Role.ORGANIZER);
        venueId = body(post("/venues", organizer, """
                {"name": "Campus Hall", "city": "Davis", "sections": [
                  {"name": "Floor", "rows": [{"label": "A", "seats": 4, "accessible": [1]}, {"label": "B", "seats": 4}]},
                  {"name": "Balcony", "rows": [{"label": "A", "seats": 3, "limitedView": [3]}]}
                ]}""").andExpect(status().isCreated())).get("id").asText();
    }

    String createEvent() throws Exception {
        String startsAt = Instant.now().plus(30, ChronoUnit.DAYS).toString();
        String earlyBirdOver = Instant.now().minus(1, ChronoUnit.DAYS).toString();
        return body(post("/events", organizer, """
                {"venueId": "%s", "name": "Spring Concert", "startsAt": "%s", "perFanLimit": 4,
                 "feeBps": 1000, "feeFlatCents": 150, "taxBps": 800,
                 "tiers": [
                   {"name": "Floor", "kind": "STANDARD", "priceCents": 5000, "sections": ["Floor"]},
                   {"name": "Early bird", "kind": "EARLY_BIRD", "priceCents": 3500, "endsAt": "%s", "sections": ["Floor"]},
                   {"name": "Student", "kind": "STUDENT", "priceCents": 2500, "sections": ["Balcony"]}
                 ]}""".formatted(venueId, startsAt, earlyBirdOver)).andExpect(status().isCreated())).get("id").asText();
    }

    @Test
    void venueSeatMapHasLabelsLayoutAndAccessibility() throws Exception {
        JsonNode venue = body(mvc.perform(get("/venues/" + venueId)).andExpect(status().isOk()));
        JsonNode floor = venue.get("sections").get(0);
        assertThat(floor.get("seats")).hasSize(8);
        assertThat(floor.get("seats").get(0).get("label").asText()).isEqualTo("FLOOR-A-1");
        assertThat(floor.get("seats").get(0).get("accessible").asBoolean()).isTrue();
        JsonNode balcony = venue.get("sections").get(1);
        assertThat(balcony.get("seats").get(2).get("limitedView").asBoolean()).isTrue();
        // balcony sits to the right of the floor, with a gap
        assertThat(balcony.get("seats").get(0).get("x").asInt()).isEqualTo(4 + 3);
    }

    @Test
    void draftsAreHiddenUntilPublishedAndPricesAreAllIn() throws Exception {
        String eventId = createEvent();
        mvc.perform(get("/events/" + eventId)).andExpect(status().isNotFound());
        mvc.perform(get("/events/" + eventId).header("Authorization", organizer)).andExpect(status().isOk());

        post("/events/" + eventId + "/publish", organizer, "{}").andExpect(status().isNoContent());
        JsonNode event = body(mvc.perform(get("/events/" + eventId)).andExpect(status().isOk()));
        // the expired early bird tier is gone; prices include fees and tax
        assertThat(event.get("tiers")).hasSize(2);
        assertThat(event.get("tiers").get(1).get("allInCents").asLong()).isEqualTo(6102);
        assertThat(event.get("fromCents").asLong()).isEqualTo(event.get("tiers").get(0).get("allInCents").asLong());
    }

    @Test
    void organizersCanOnlyTouchTheirOwnEvents() throws Exception {
        String eventId = createEvent();
        String someoneElse = testUsers.bearer(Role.ORGANIZER);
        post("/events/" + eventId + "/publish", someoneElse, "{}").andExpect(status().isForbidden());
        post("/events/" + eventId + "/promo-codes", someoneElse, """
                {"code": "FREE", "percentOff": 100, "maxUses": 1000}""").andExpect(status().isForbidden());
        post("/events/" + eventId + "/publish", testUsers.bearer(Role.ADMIN), "{}").andExpect(status().isNoContent());
    }

    @Test
    void quotesApplyPromoCodesAndCheckTiers() throws Exception {
        String eventId = createEvent();
        post("/events/" + eventId + "/publish", organizer, "{}").andExpect(status().isNoContent());
        post("/events/" + eventId + "/promo-codes", organizer, """
                {"code": "spring20", "percentOff": 20, "maxUses": 100}""").andExpect(status().isCreated());
        JsonNode event = body(mvc.perform(get("/events/" + eventId)));
        String floorTier = tierId(event, "Floor");
        String studentTier = tierId(event, "Student");

        String fan = testUsers.bearer(Role.FAN);
        JsonNode quote = body(post("/events/" + eventId + "/quote", fan, """
                {"items": [{"seat": "FLOOR-A-2", "tierId": "%s"}, {"seat": "FLOOR-A-3", "tierId": "%s"}],
                 "promoCode": "SPRING20"}""".formatted(floorTier, floorTier)).andExpect(status().isOk()));
        assertThat(quote.get("totalCents").asLong()).isEqualTo(2 * 4914);

        post("/events/" + eventId + "/quote", fan, """
                {"items": [{"seat": "BALCONY-A-1", "tierId": "%s"}]}""".formatted(floorTier))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("seat_not_in_tier"));
        post("/events/" + eventId + "/quote", fan, """
                {"items": [{"seat": "BALCONY-A-1", "tierId": "%s"}]}""".formatted(studentTier))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("student_email_required"));
        String student = testUsers.bearer(Role.FAN, "s-" + UUID.randomUUID() + "@ucdavis.edu");
        post("/events/" + eventId + "/quote", student, """
                {"items": [{"seat": "BALCONY-A-1", "tierId": "%s"}]}""".formatted(studentTier)).andExpect(status().isOk());
        post("/events/" + eventId + "/quote", fan, """
                {"items": [{"seat": "FLOOR-A-2", "tierId": "%s"}], "promoCode": "NOPE"}""".formatted(floorTier))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_promo_code"));
    }

    @Test
    void perFanLimitAndExpiredPromoCodesAreRefused() throws Exception {
        String eventId = createEvent();
        post("/events/" + eventId + "/publish", organizer, "{}").andExpect(status().isNoContent());
        String expired = Instant.now().minus(1, ChronoUnit.HOURS).toString();
        post("/events/" + eventId + "/promo-codes", organizer, """
                {"code": "LASTWEEK", "percentOff": 50, "maxUses": 10, "expiresAt": "%s"}""".formatted(expired))
                .andExpect(status().isCreated());
        String floorTier = tierId(body(mvc.perform(get("/events/" + eventId))), "Floor");
        String fan = testUsers.bearer(Role.FAN);

        // the event allows 4 per fan
        StringBuilder five = new StringBuilder();
        for (int n = 1; n <= 5; n++) {
            five.append(n > 1 ? "," : "").append("""
                    {"seat": "FLOOR-B-%d", "tierId": "%s"}""".formatted(n == 5 ? 1 : n, floorTier).strip());
        }
        post("/events/" + eventId + "/quote", fan, "{\"items\": [" + five + "]}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("over_limit"));
        post("/events/" + eventId + "/quote", fan, """
                {"items": [{"seat": "FLOOR-B-1", "tierId": "%s"}], "promoCode": "LASTWEEK"}""".formatted(floorTier))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_promo_code"));
    }

    @Test
    void publishingPutsTheSeatsInTheLedger() throws Exception {
        String eventId = createEvent();
        mvc.perform(get("/events/" + eventId + "/seats")).andExpect(status().isNotFound());
        post("/events/" + eventId + "/publish", organizer, "{}").andExpect(status().isNoContent());

        // every seat covered by a tier, all free, read back from the ledger
        JsonNode seats = body(mvc.perform(get("/events/" + eventId + "/seats")).andExpect(status().isOk()));
        assertThat(seats).hasSize(11);
        assertThat(seats).allSatisfy(s -> assertThat(s.get("status").asText()).isEqualTo("FREE"));
        assertThat(seats.findValuesAsText("seat")).contains("FLOOR-A-1", "BALCONY-A-3");

        // a held seat shows up as held to everyone
        ledger.write(new SeatCommand.Hold(eventId, List.of("FLOOR-A-2"), "fan-1", "h-" + UUID.randomUUID(), 60_000));
        JsonNode after = body(mvc.perform(get("/events/" + eventId + "/seats")));
        assertThat(after.findValuesAsText("status")).containsOnlyOnce("HELD");
        assertThat(after.toString()).doesNotContain("fan-1");
    }

    @Test
    void aDemotedOrganizersOldTokenStopsWorking() throws Exception {
        String eventId = createEvent();
        String me = body(mvc.perform(get("/me").header("Authorization", organizer))).get("id").asText();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/admin/users/" + me + "/role")
                .header("Authorization", testUsers.bearer(Role.ADMIN))
                .contentType(MediaType.APPLICATION_JSON).content("{\"role\": \"FAN\"}"))
                .andExpect(status().isNoContent());

        // the token still says ORGANIZER and hasn't expired, but it was issued before the change
        post("/events/" + eventId + "/publish", organizer, "{}").andExpect(status().isUnauthorized());
        mvc.perform(get("/me").header("Authorization", organizer)).andExpect(status().isUnauthorized());
    }

    @Test
    void bestAvailablePicksSeatsTogetherNearTheFront() throws Exception {
        String eventId = createEvent();
        post("/events/" + eventId + "/publish", organizer, "{}").andExpect(status().isNoContent());
        String floor = tierId(body(mvc.perform(get("/events/" + eventId))), "Floor");
        String best = "/events/" + eventId + "/best-seats?tierId=" + floor;

        // row A has 4 seats: the middle pair wins
        assertThat(texts(body(mvc.perform(get(best + "&count=2"))).get("seats"))).containsExactly("FLOOR-A-2", "FLOOR-A-3");

        // take A-2: no pair is left together in row A except A-3/A-4, which is still in front of row B
        ledger.write(new SeatCommand.Hold(eventId, List.of("FLOOR-A-2"), "fan-1", "h-" + UUID.randomUUID(), 60_000));
        assertThat(texts(body(mvc.perform(get(best + "&count=2"))).get("seats"))).containsExactly("FLOOR-A-3", "FLOOR-A-4");
        // three together only fits in row B now
        assertThat(texts(body(mvc.perform(get(best + "&count=3"))).get("seats"))).containsExactly("FLOOR-B-1", "FLOOR-B-2", "FLOOR-B-3");
        // only A-1 is accessible
        assertThat(texts(body(mvc.perform(get(best + "&count=1&accessible=true"))).get("seats"))).containsExactly("FLOOR-A-1");
        mvc.perform(get(best + "&count=5")).andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("no_seats_together"));
    }

    static List<String> texts(JsonNode array) {
        List<String> out = new java.util.ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }

    static String tierId(JsonNode event, String name) {
        for (JsonNode t : event.get("tiers")) {
            if (t.get("name").asText().equals(name)) {
                return t.get("id").asText();
            }
        }
        throw new AssertionError("no tier " + name);
    }
}
