package dev.seatline.api.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.seatline.api.TestDatabase;
import dev.seatline.api.TestLedger;
import dev.seatline.api.TestUsers;
import dev.seatline.api.account.Role;
import dev.seatline.api.seats.Seats;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** In a rush, fans polling the same seat map share one ledger read instead of each making their own. */
@SpringBootTest(properties = "seatline.seat-map-cache-ms=60000")
@AutoConfigureMockMvc
@Import({TestDatabase.class, TestLedger.class, TestUsers.class})
class SeatMapSharingTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestUsers testUsers;
    @MockitoSpyBean Seats seats;

    String organizer;
    String eventId;

    @BeforeEach
    void draftEvent() throws Exception {
        organizer = testUsers.bearer(Role.ORGANIZER);
        String venueId = body(post("/venues", """
                {"name": "Hall", "city": "Davis", "sections": [{"name": "Floor", "rows": [{"label": "A", "seats": 6}]}]}""")
                .andExpect(status().isCreated())).get("id").asText();
        eventId = body(post("/events", """
                {"venueId": "%s", "name": "Rush", "startsAt": "%s", "perFanLimit": 4,
                 "feeBps": 0, "feeFlatCents": 0, "taxBps": 0,
                 "tiers": [{"name": "Floor", "kind": "STANDARD", "priceCents": 1000, "sections": ["Floor"]}]}"""
                .formatted(venueId, Instant.now().plus(30, ChronoUnit.DAYS))).andExpect(status().isCreated()))
                .get("id").asText();
    }

    @Test
    void fiftyFansPollingAtOnceCostOneLedgerRead() throws Exception {
        post("/events/" + eventId + "/publish", "{}").andExpect(status().isNoContent());
        clearInvocations(seats);

        CountDownLatch go = new CountDownLatch(1);
        List<Future<JsonNode>> maps = new ArrayList<>();
        try (ExecutorService fans = Executors.newFixedThreadPool(50)) {
            for (int i = 0; i < 50; i++) {
                maps.add(fans.submit(() -> {
                    go.await();
                    return body(mvc.perform(get("/events/" + eventId + "/seats")).andExpect(status().isOk()));
                }));
            }
            go.countDown();
            for (Future<JsonNode> m : maps) {
                assertThat(m.get()).hasSize(6);
            }
        }
        verify(seats, atMost(1)).statuses(anyString());
    }

    @Test
    void aDraftsAnswerIsntKeptOncePublished() throws Exception {
        mvc.perform(get("/events/" + eventId + "/seats")).andExpect(status().isNotFound());
        post("/events/" + eventId + "/publish", "{}").andExpect(status().isNoContent());

        JsonNode map = body(mvc.perform(get("/events/" + eventId + "/seats")).andExpect(status().isOk()));
        assertThat(map).hasSize(6);
    }

    ResultActions post(String path, String body) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                .header("Authorization", organizer).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    JsonNode body(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }
}
