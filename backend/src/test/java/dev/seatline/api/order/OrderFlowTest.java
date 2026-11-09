package dev.seatline.api.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.seatline.api.TestDatabase;
import dev.seatline.api.TestLedger;
import dev.seatline.api.TestUsers;
import dev.seatline.api.account.Role;
import dev.seatline.api.payment.FakePayments;
import dev.seatline.api.payment.Payments;
import dev.seatline.api.seats.Seats;
import dev.seatline.ledger.seats.SeatCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import({TestDatabase.class, TestLedger.class, TestUsers.class})
class OrderFlowTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestUsers testUsers;
    @Autowired Seats ledger;
    @Autowired FakePayments payments;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean OrderService orders;

    String organizer;
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

    @BeforeEach
    void onSaleEvent() throws Exception {
        organizer = testUsers.bearer(Role.ORGANIZER);
        String venue = body(call(HttpMethod.POST, "/venues", organizer, """
                {"name": "Club", "city": "Davis", "sections": [
                  {"name": "Floor", "rows": [{"label": "A", "seats": 6}]}]}""")).get("id").asText();
        eventId = body(call(HttpMethod.POST, "/events", organizer, """
                {"venueId": "%s", "name": "Late Show", "startsAt": "%s", "perFanLimit": 4,
                 "feeBps": 1000, "feeFlatCents": 150, "taxBps": 800,
                 "tiers": [{"name": "GA", "kind": "STANDARD", "priceCents": 5000, "sections": ["Floor"]}]}"""
                .formatted(venue, Instant.now().plus(30, ChronoUnit.DAYS)))).get("id").asText();
        call(HttpMethod.POST, "/events/" + eventId + "/publish", organizer, "{}").andExpect(status().isNoContent());
        call(HttpMethod.POST, "/events/" + eventId + "/promo-codes", organizer, """
                {"code": "HALF", "percentOff": 50, "maxUses": 10}""").andExpect(status().isCreated());
        tier = body(mvc.perform(request(HttpMethod.GET, "/events/" + eventId))).get("tiers").get(0).get("id").asText();
    }

    ResultActions hold(String fan, String promo, String... seats) throws Exception {
        List<String> items = new ArrayList<>();
        for (String s : seats) {
            items.add("{\"seat\": \"%s\", \"tierId\": \"%s\"}".formatted(s, tier));
        }
        String p = promo == null ? "" : ", \"promoCode\": \"" + promo + "\"";
        return call(HttpMethod.POST, "/events/" + eventId + "/holds", fan, "{\"items\": " + items + p + "}");
    }

    ResultActions checkout(String fan, String holdId, String card) throws Exception {
        return call(HttpMethod.POST, "/holds/" + holdId + "/checkout", fan, "{\"cardToken\": \"" + card + "\"}");
    }

    String seatStatus(String seat) throws Exception {
        for (JsonNode s : body(mvc.perform(request(HttpMethod.GET, "/events/" + eventId + "/seats")))) {
            if (s.get("seat").asText().equals(seat)) {
                return s.get("status").asText();
            }
        }
        throw new AssertionError("no seat " + seat);
    }

    @Test
    void holdPayAndGetTickets() throws Exception {
        String email = "fan-" + UUID.randomUUID() + "@example.com";
        String fan = testUsers.bearer(Role.FAN, email);
        JsonNode h = body(hold(fan, "half", "FLOOR-A-1", "FLOOR-A-2").andExpect(status().isCreated()));
        assertThat(seatStatus("FLOOR-A-1")).isEqualTo("HELD");
        // 50.00 face, half off = 25.00, fee 10% + 1.50 = 4.00, tax 8% of 29.00 = 2.32
        assertThat(h.get("quote").get("totalCents").asLong()).isEqualTo(2 * 3132);

        JsonNode order = body(checkout(fan, h.get("holdId").asText(), "tok_visa").andExpect(status().isOk()));
        assertThat(order.get("totalCents").asLong()).isEqualTo(6264);
        assertThat(order.get("tickets").findValuesAsText("seat")).containsExactly("FLOOR-A-1", "FLOOR-A-2");
        assertThat(seatStatus("FLOOR-A-1")).isEqualTo("SOLD");
        assertThat(body(call(HttpMethod.GET, "/me/tickets", fan, null))).hasSize(2);

        assertThat(jdbc.queryForObject("select used from promo_codes where code = 'HALF' and event_id = ?::uuid",
                Integer.class, eventId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select body from outbox_emails where to_address = ? and subject like '%tickets%'",
                String.class, email)).contains("FLOOR-A-2").contains("$62.64");
    }

    @Test
    void aFanCanLookUpTheirOwnHoldAndSeeItGetPaid() throws Exception {
        String fan = testUsers.bearer(Role.FAN);
        String holdId = body(hold(fan, null, "FLOOR-A-5").andExpect(status().isCreated())).get("holdId").asText();

        JsonNode open = body(call(HttpMethod.GET, "/holds/" + holdId, fan, null).andExpect(status().isOk()));
        assertThat(open.get("state").asText()).isEqualTo("OPEN");
        assertThat(open.get("eventId").asText()).isEqualTo(eventId);
        assertThat(open.get("quote").get("lines").findValuesAsText("seat")).containsExactly("FLOOR-A-5");

        // someone else's hold looks like no hold at all
        call(HttpMethod.GET, "/holds/" + holdId, testUsers.bearer(Role.FAN), null).andExpect(status().isNotFound());

        String orderId = body(checkout(fan, holdId, "tok_visa")).get("id").asText();
        JsonNode paid = body(call(HttpMethod.GET, "/holds/" + holdId, fan, null));
        assertThat(paid.get("state").asText()).isEqualTo("PAID");
        assertThat(paid.get("orderId").asText()).isEqualTo(orderId);
    }

    @Test
    void aReleasedHoldSaysSo() throws Exception {
        String fan = testUsers.bearer(Role.FAN);
        String holdId = body(hold(fan, null, "FLOOR-A-6")).get("holdId").asText();
        call(HttpMethod.DELETE, "/holds/" + holdId, fan, null).andExpect(status().isNoContent());
        assertThat(body(call(HttpMethod.GET, "/holds/" + holdId, fan, null)).get("state").asText()).isEqualTo("RELEASED");
    }

    @Test
    void aHoldThatsBeenLetGoOrHasRunOutIsNeverCharged() throws Exception {
        String fan = testUsers.bearer(Role.FAN);
        String released = body(hold(fan, null, "FLOOR-A-1")).get("holdId").asText();
        call(HttpMethod.DELETE, "/holds/" + released, fan, null).andExpect(status().isNoContent());
        checkout(fan, released, "tok_visa").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("no_such_hold"));

        String ranOut = body(hold(fan, null, "FLOOR-A-2")).get("holdId").asText();
        jdbc.update("update holds set expires_at = now() - interval '1 second' where id = ?", ranOut);
        checkout(fan, ranOut, "tok_visa").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("hold_expired"));

        assertThat(jdbc.queryForObject("select count(*) from payment_attempts where hold_id in (?, ?)", Long.class,
                released, ranOut)).isZero();
    }

    @Test
    void aFreeOrderNeverReachesTheCardProcessor() throws Exception {
        String venue = body(call(HttpMethod.POST, "/venues", organizer, """
                {"name": "Park", "city": "Davis", "sections": [{"name": "Lawn", "rows": [{"label": "A", "seats": 2}]}]}"""))
                .get("id").asText();
        String free = body(call(HttpMethod.POST, "/events", organizer, """
                {"venueId": "%s", "name": "Free Show", "startsAt": "%s", "perFanLimit": 2,
                 "feeBps": 0, "feeFlatCents": 0, "taxBps": 0,
                 "tiers": [{"name": "Free", "kind": "STANDARD", "priceCents": 0, "sections": ["Lawn"]}]}"""
                .formatted(venue, Instant.now().plus(30, ChronoUnit.DAYS)))).get("id").asText();
        call(HttpMethod.POST, "/events/" + free + "/publish", organizer, "{}").andExpect(status().isNoContent());
        String freeTier = body(mvc.perform(request(HttpMethod.GET, "/events/" + free))).get("tiers").get(0).get("id").asText();

        String fan = testUsers.bearer(Role.FAN);
        String holdId = body(call(HttpMethod.POST, "/events/" + free + "/holds", fan,
                "{\"items\": [{\"seat\": \"LAWN-A-1\", \"tierId\": \"" + freeTier + "\"}]}")
                .andExpect(status().isCreated())).get("holdId").asText();

        // a card that would be declined doesn't matter: nothing is charged
        JsonNode order = body(checkout(fan, holdId, "tok_declined").andExpect(status().isOk()));
        assertThat(order.get("totalCents").asLong()).isZero();
        assertThat(jdbc.queryForObject("select payment_ref from orders where hold_id = ?", String.class, holdId))
                .startsWith("free_");

        // and handing it back has nothing to refund, but still frees the seat
        String ticket = order.get("tickets").get(0).get("id").asText();
        call(HttpMethod.POST, "/tickets/" + ticket + "/return", fan, null).andExpect(status().isNoContent());
        orders.reconcile();
        assertThat(jdbc.queryForObject("select count(*) from refunds where state <> 'DONE' and payment_ref like 'free_%'",
                Long.class)).isZero();
    }

    @Test
    void cancellingAnEventRefundsEveryOrderInFullOnce() throws Exception {
        String fan = testUsers.bearer(Role.FAN);
        JsonNode first = body(checkout(fan, body(hold(fan, null, "FLOOR-A-1", "FLOOR-A-2")).get("holdId").asText(), "tok_visa")
                .andExpect(status().isOk()));
        // one of the two was already handed back (face value refunded, fees kept)
        call(HttpMethod.POST, "/tickets/" + first.get("tickets").get(0).get("id").asText() + "/return", fan, null)
                .andExpect(status().isNoContent());
        String other = testUsers.bearer(Role.FAN);
        JsonNode second = body(checkout(other, body(hold(other, "HALF", "FLOOR-A-3")).get("holdId").asText(), "tok_visa")
                .andExpect(status().isOk()));
        // and someone is halfway through checkout when it's called off
        String late = testUsers.bearer(Role.FAN);
        String openHold = body(hold(late, null, "FLOOR-A-4")).get("holdId").asText();

        call(HttpMethod.POST, "/events/" + eventId + "/cancel", testUsers.bearer(Role.ORGANIZER), null)
                .andExpect(status().isForbidden());
        call(HttpMethod.POST, "/events/" + eventId + "/cancel", organizer, null).andExpect(status().isNoContent());
        call(HttpMethod.POST, "/events/" + eventId + "/cancel", organizer, null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("already_cancelled"));

        for (JsonNode order : List.of(first, second)) {
            String ref = jdbc.queryForObject("select payment_ref from orders where id = ?::uuid", String.class,
                    order.get("id").asText());
            assertThat(payments.refundedOn(ref)).isEqualTo(order.get("totalCents").asLong());
        }
        assertThat(jdbc.queryForObject("select count(*) from tickets where event_id = ?::uuid and status = 'VALID'",
                Long.class, eventId)).isZero();
        assertThat(jdbc.queryForObject("""
                select count(*) from outbox_emails where subject = 'Cancelled: Late Show'
                and created_at > now() - interval '1 minute'""", Long.class)).isGreaterThanOrEqualTo(2);

        checkout(late, openHold, "tok_visa").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("event_cancelled"));
        hold(late, null, "FLOOR-A-5").andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("not_on_sale"));

        // the reconciler running later doesn't refund anyone twice
        orders.reconcile();
        String ref = jdbc.queryForObject("select payment_ref from orders where id = ?::uuid", String.class,
                first.get("id").asText());
        assertThat(payments.refundedOn(ref)).isEqualTo(first.get("totalCents").asLong());
    }

    @Test
    void theOrganizerSeesLiveSalesForTheirEventOnly() throws Exception {
        String fan = testUsers.bearer(Role.FAN);
        String sold = body(hold(fan, null, "FLOOR-A-1", "FLOOR-A-2")).get("holdId").asText();
        checkout(fan, sold, "tok_visa").andExpect(status().isOk());
        hold(testUsers.bearer(Role.FAN), null, "FLOOR-A-3").andExpect(status().isCreated());

        JsonNode stats = body(call(HttpMethod.GET, "/events/" + eventId + "/stats", organizer, null).andExpect(status().isOk()));
        assertThat(stats.get("seats").asInt()).isEqualTo(6);
        assertThat(stats.get("sold").asInt()).isEqualTo(2);
        assertThat(stats.get("held").asInt()).isEqualTo(1);
        assertThat(stats.get("free").asInt()).isEqualTo(3);
        assertThat(stats.get("orders").asLong()).isEqualTo(1);
        assertThat(stats.get("tickets").asLong()).isEqualTo(2);
        // 50.00 face, fee 10% + 1.50 = 6.50, tax 8% of 56.50 = 4.52: 61.02 a seat
        assertThat(stats.get("revenueCents").asLong()).isEqualTo(2 * 6102);

        assertThat(body(call(HttpMethod.GET, "/organizer/events", organizer, null)).findValuesAsText("id")).contains(eventId);
        String other = testUsers.bearer(Role.ORGANIZER);
        call(HttpMethod.GET, "/events/" + eventId + "/stats", other, null).andExpect(status().isForbidden());
        assertThat(body(call(HttpMethod.GET, "/organizer/events", other, null)).findValuesAsText("id")).doesNotContain(eventId);
    }

    @Test
    void aPromoCodeStopsWorkingOnceItsUsesAreGone() throws Exception {
        call(HttpMethod.POST, "/events/" + eventId + "/promo-codes", organizer, """
                {"code": "ONCE", "percentOff": 10, "maxUses": 1}""").andExpect(status().isCreated());
        String fan = testUsers.bearer(Role.FAN);
        String holdId = body(hold(fan, "ONCE", "FLOOR-A-3").andExpect(status().isCreated())).get("holdId").asText();
        checkout(fan, holdId, "tok_visa").andExpect(status().isOk());
        hold(testUsers.bearer(Role.FAN), "ONCE", "FLOOR-A-4").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_promo_code"));
    }

    @Test
    void theLastPromoUseCantGoToTwoFansAndComesBackIfUnpaid() throws Exception {
        call(HttpMethod.POST, "/events/" + eventId + "/promo-codes", organizer, """
                {"code": "LAST", "percentOff": 10, "maxUses": 1}""").andExpect(status().isCreated());
        String first = testUsers.bearer(Role.FAN);
        String holdId = body(hold(first, "LAST", "FLOOR-A-1").andExpect(status().isCreated())).get("holdId").asText();
        // nobody has paid yet, but the use is taken
        hold(testUsers.bearer(Role.FAN), "LAST", "FLOOR-A-2").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_promo_code"));

        call(HttpMethod.DELETE, "/holds/" + holdId, first, null).andExpect(status().isNoContent());
        hold(testUsers.bearer(Role.FAN), "LAST", "FLOOR-A-2").andExpect(status().isCreated());
    }

    @Test
    void twoCheckoutsOfOneHoldAtOnceGiveOneOrder() throws Exception {
        String fan = testUsers.bearer(Role.FAN);
        String holdId = body(hold(fan, null, "FLOOR-A-3")).get("holdId").asText();
        Callable<JsonNode> pay = () -> body(checkout(fan, holdId, "tok_visa").andExpect(status().isOk()));
        List<JsonNode> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Future<JsonNode> f : pool.invokeAll(List.of(pay, pay))) {
                results.add(f.get());
            }
        }
        assertThat(results.get(1).get("id")).isEqualTo(results.get(0).get("id"));
        assertThat(jdbc.queryForObject("select count(*) from tickets where order_id = ?::uuid", Integer.class,
                results.get(0).get("id").asText())).isEqualTo(1);
    }

    @Test
    void tenClicksOnPayWithDifferentCardsChargeOnce() throws Exception {
        String fan = testUsers.bearer(Role.FAN);
        String holdId = body(hold(fan, null, "FLOOR-A-6")).get("holdId").asText();
        List<Callable<JsonNode>> clicks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String card = i % 2 == 0 ? "tok_visa" : "tok_mastercard";
            clicks.add(() -> body(checkout(fan, holdId, card).andExpect(status().isOk())));
        }
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Future<JsonNode> f : pool.invokeAll(clicks)) {
                assertThat(f.get().get("tickets")).hasSize(1);
            }
        }
        assertThat(jdbc.queryForObject("select count(*) from payment_attempts where hold_id = ?", Integer.class, holdId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from refunds where id like ?", Integer.class, "%" + holdId + "%"))
                .isZero();
    }

    @Test
    void afterADeclineAnotherCardCanPayButMadeUpCardsCant() throws Exception {
        String fan = testUsers.bearer(Role.FAN);
        String holdId = body(hold(fan, null, "FLOOR-A-5")).get("holdId").asText();
        checkout(fan, holdId, "tok_declined").andExpect(status().isPaymentRequired());
        checkout(fan, holdId, "4242424242424242").andExpect(status().isPaymentRequired());
        checkout(fan, holdId, "tok_visa").andExpect(status().isOk());
        assertThat(jdbc.queryForList("select state from payment_attempts where hold_id = ? order by created_at",
                String.class, holdId)).containsExactly("DECLINED", "DECLINED", "DONE");
    }

    /** Runs checkout with the process "dying" at one step, then lets the reconciler clean up. */
    String crashThenReconcile(String step, boolean holdLostMeanwhile) throws Exception {
        String holdId = crashAt(step, "FLOOR-A-4");
        if (holdLostMeanwhile) {
            ledger.write(new SeatCommand.Release(holdId));
        }
        orders.reconcileNow(Duration.ofMinutes(1));
        return holdId;
    }

    /** Checkout "dies" at a step; the attempt is made old enough for the reconciler to pick up. */
    String crashAt(String step, String seat) throws Exception {
        String fan = testUsers.bearer(Role.FAN);
        String holdId = body(hold(fan, null, seat)).get("holdId").asText();
        doThrow(new IllegalStateException("simulated crash")).when(orders).step(step);
        try {
            assertThatThrownBy(() -> checkout(fan, holdId, "tok_visa")).hasRootCauseMessage("simulated crash");
        } finally {
            doNothing().when(orders).step(step);
        }
        jdbc.update("update payment_attempts set created_at = now() - interval '5 minutes' where hold_id = ?", holdId);
        return holdId;
    }

    @Test
    void oneBadAttemptDoesntStopTheOthers() throws Exception {
        String a = crashAt("charged", "FLOOR-A-5");
        String b = crashAt("charged", "FLOOR-A-6");
        // the first attempt the reconciler tries blows up; the second must still be finished
        doThrow(new IllegalStateException("bad row")).doNothing().when(orders).step("confirmed");
        orders.reconcileNow(Duration.ofMinutes(1));
        assertThat(orders(a) + orders(b)).isEqualTo(1);

        orders.reconcileNow(Duration.ofMinutes(1)); // and the bad one is retried next round
        assertThat(orders(a) + orders(b)).isEqualTo(2);
    }

    int orders(String holdId) {
        return jdbc.queryForObject("select count(*) from orders where hold_id = ?", Integer.class, holdId);
    }

    long refunded(String holdId) {
        String ref = jdbc.queryForObject("select payment_ref from payment_attempts where hold_id = ?", String.class, holdId);
        return payments.refundedOn(ref);
    }

    @Test
    void diedAfterChargingAndTheHoldIsStillThereSoTheOrderIsFinished() throws Exception {
        String holdId = crashThenReconcile("charged", false);
        assertThat(orders(holdId)).isEqualTo(1);
        assertThat(refunded(holdId)).isZero();
        assertThat(seatStatus("FLOOR-A-4")).isEqualTo("SOLD");
    }

    @Test
    void diedAfterChargingAndTheHoldIsGoneSoTheMoneyGoesBack() throws Exception {
        String holdId = crashThenReconcile("charged", true);
        assertThat(orders(holdId)).isZero();
        assertThat(refunded(holdId)).isEqualTo(jdbc.queryForObject("select total_cents from holds where id = ?", Long.class, holdId));
    }

    @Test
    void diedAfterTheLedgerConfirmedSoTheOrderIsWritten() throws Exception {
        String holdId = crashThenReconcile("confirmed", false);
        assertThat(orders(holdId)).isEqualTo(1);
        assertThat(refunded(holdId)).isZero();
    }

    @Test
    void retryingCheckoutGivesTheSameOrderAndChargesOnce() throws Exception {
        String fan = testUsers.bearer(Role.FAN);
        String holdId = body(hold(fan, null, "FLOOR-A-3")).get("holdId").asText();
        JsonNode first = body(checkout(fan, holdId, "tok_visa").andExpect(status().isOk()));
        JsonNode again = body(checkout(fan, holdId, "tok_visa").andExpect(status().isOk()));
        assertThat(again.get("id")).isEqualTo(first.get("id"));
        assertThat(jdbc.queryForObject("select count(*) from orders where hold_id = ?", Integer.class, holdId)).isEqualTo(1);
    }

    @Test
    void aDeclinedCardKeepsTheHoldAndAnotherCardWorks() throws Exception {
        String fan = testUsers.bearer(Role.FAN);
        String holdId = body(hold(fan, null, "FLOOR-A-4")).get("holdId").asText();
        checkout(fan, holdId, "tok_declined").andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.error").value("payment_declined"));
        assertThat(seatStatus("FLOOR-A-4")).isEqualTo("HELD");
        checkout(fan, holdId, "tok_visa").andExpect(status().isOk());
    }

    @Test
    void paymentAfterTheHoldIsGoneIsRefunded() throws Exception {
        String fan = testUsers.bearer(Role.FAN);
        JsonNode h = body(hold(fan, null, "FLOOR-A-5"));
        String holdId = h.get("holdId").asText();
        ledger.write(new SeatCommand.Release(holdId)); // as if it had expired

        checkout(fan, holdId, "tok_visa").andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("hold_expired"));
        Payments.Charge charge = payments.charge(holdId + ":1", 0, "tok_visa", "");
        assertThat(payments.refundedOn(charge.ref())).isEqualTo(h.get("quote").get("totalCents").asLong());
        assertThat(seatStatus("FLOOR-A-5")).isEqualTo("FREE");
    }

    @Test
    void checkoutFinishesAfterACrashBetweenLedgerAndDatabase() throws Exception {
        String fan = testUsers.bearer(Role.FAN);
        String holdId = body(hold(fan, null, "FLOOR-A-6")).get("holdId").asText();
        // first attempt: paid and confirmed in the ledger, then the API died before writing Postgres
        Payments.Charge charge = payments.charge(holdId + ":1", 0, "tok_visa", "");
        assertThat(ledger.write(new SeatCommand.Confirm(holdId, charge.ref())).ok()).isTrue();

        JsonNode order = body(checkout(fan, holdId, "tok_visa").andExpect(status().isOk()));
        assertThat(order.get("tickets").get(0).get("id").asText()).isEqualTo("T-" + holdId + "-FLOOR-A-6");
        assertThat(payments.refundedOn(charge.ref())).isZero();
    }

    @Test
    void twentyFansRaceForOneSeatAndExactlyOneGetsIt() throws Exception {
        List<String> fans = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            fans.add(testUsers.bearer(Role.FAN));
        }
        List<Callable<Integer>> tries = fans.stream()
                .<Callable<Integer>>map(f -> () -> hold(f, null, "FLOOR-A-1").andReturn().getResponse().getStatus())
                .toList();
        List<Integer> codes = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Future<Integer> f : pool.invokeAll(tries)) {
                codes.add(f.get());
            }
        }
        assertThat(codes).containsOnlyOnce(201).filteredOn(c -> c != 201).containsOnly(409);
    }

    @Test
    void ticketsCanBeGivenToAFriendOrHandedBack() throws Exception {
        String fan = testUsers.bearer(Role.FAN);
        String friendEmail = "friend-" + UUID.randomUUID() + "@example.com";
        String friend = testUsers.bearer(Role.FAN, friendEmail);
        String holdId = body(hold(fan, null, "FLOOR-A-1", "FLOOR-A-2")).get("holdId").asText();
        JsonNode tickets = body(checkout(fan, holdId, "tok_visa")).get("tickets");
        String first = tickets.get(0).get("id").asText();
        String second = tickets.get(1).get("id").asText();

        call(HttpMethod.POST, "/tickets/" + first + "/transfer", fan, "{\"toEmail\": \"" + friendEmail + "\"}")
                .andExpect(status().isNoContent());
        assertThat(body(call(HttpMethod.GET, "/me/tickets", friend, null)).findValuesAsText("id")).containsExactly(first);
        assertThat(ledger.ticket(first).orElseThrow().version()).isEqualTo(2); // old QR code is dead
        // it's not the sender's any more
        call(HttpMethod.POST, "/tickets/" + first + "/return", fan, null).andExpect(status().isNotFound());
        // and the friend can't cash it in either: the refund would go to the buyer's card
        call(HttpMethod.POST, "/tickets/" + first + "/return", friend, null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("only_the_buyer_can_return"));

        call(HttpMethod.POST, "/tickets/" + second + "/return", fan, null).andExpect(status().isNoContent());
        assertThat(seatStatus("FLOOR-A-2")).isEqualTo("FREE");
        assertThat(body(call(HttpMethod.GET, "/me/tickets", fan, null))).isEmpty();
        String ref = jdbc.queryForObject("select payment_ref from orders where hold_id = ?", String.class, holdId);
        assertThat(payments.refundedOn(ref)).isEqualTo(tickets.get(1).get("priceCents").asLong());
    }

    @Test
    void aTicketCantBeReturnedAfterItsScannedOrTheShowStarts() throws Exception {
        String fan = testUsers.bearer(Role.FAN);
        String holdId = body(hold(fan, null, "FLOOR-A-3", "FLOOR-A-4")).get("holdId").asText();
        JsonNode tickets = body(checkout(fan, holdId, "tok_visa")).get("tickets");
        String scanned = tickets.get(0).get("id").asText();
        String other = tickets.get(1).get("id").asText();

        jdbc.update("update tickets set scanned_at = now() where id = ?", scanned);
        call(HttpMethod.POST, "/tickets/" + scanned + "/return", fan, null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("too_late_to_return"));

        jdbc.update("update events set starts_at = now() - interval '1 minute' where id = ?::uuid", eventId);
        call(HttpMethod.POST, "/tickets/" + other + "/return", fan, null).andExpect(status().isConflict());
        assertThat(seatStatus("FLOOR-A-3")).isEqualTo("SOLD");
        assertThat(seatStatus("FLOOR-A-4")).isEqualTo("SOLD");
    }

    @Test
    void aReturnAndACancelAtTheSameMomentRefundTheOrderOnce() throws Exception {
        for (int round = 0; round < 6; round++) {
            if (round > 0) {
                onSaleEvent(); // the last one is cancelled
            }
            String fan = testUsers.bearer(Role.FAN);
            JsonNode order = body(checkout(fan, body(hold(fan, null, "FLOOR-A-1", "FLOOR-A-2")).get("holdId").asText(), "tok_visa")
                    .andExpect(status().isOk()));
            String ticket = order.get("tickets").get(0).get("id").asText();
            List<Callable<Integer>> both = List.of(
                    () -> call(HttpMethod.POST, "/tickets/" + ticket + "/return", fan, null).andReturn().getResponse().getStatus(),
                    () -> call(HttpMethod.POST, "/events/" + eventId + "/cancel", organizer, null).andReturn().getResponse().getStatus());
            try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
                for (Future<Integer> f : pool.invokeAll(both)) {
                    f.get();
                }
            }
            String ref = jdbc.queryForObject("select payment_ref from orders where id = ?::uuid", String.class,
                    order.get("id").asText());
            assertThat(payments.refundedOn(ref)).as("round %d", round).isEqualTo(order.get("totalCents").asLong());
        }
    }

    @Test
    void payingWhileTheEventIsCalledOffIsRefundedAndSaysSo() throws Exception {
        String fan = testUsers.bearer(Role.FAN);
        String holdId = body(hold(fan, null, "FLOOR-A-1")).get("holdId").asText();
        // the card has gone through and the ledger confirmed, then the cancel lands before the order is saved
        doThrow(new IllegalStateException("stop")).when(orders).step("confirmed");
        try {
            assertThatThrownBy(() -> checkout(fan, holdId, "tok_visa")).hasRootCauseMessage("stop");
        } finally {
            doNothing().when(orders).step("confirmed");
        }
        call(HttpMethod.POST, "/events/" + eventId + "/cancel", organizer, null).andExpect(status().isNoContent());

        checkout(fan, holdId, "tok_visa").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("event_cancelled"));
        String ref = jdbc.queryForObject("select payment_ref from orders where hold_id = ?", String.class, holdId);
        long total = jdbc.queryForObject("select total_cents from orders where hold_id = ?", Long.class, holdId);
        assertThat(payments.refundedOn(ref)).isEqualTo(total);
        assertThat(jdbc.queryForObject("select count(*) from tickets where order_id = (select id from orders where hold_id = ?) and status = 'VALID'",
                Long.class, holdId)).isZero();
        String email = jdbc.queryForObject("select email from users u join holds h on h.fan_id = u.id where h.id = ?", String.class, holdId);
        assertThat(jdbc.queryForList("select subject from outbox_emails where to_address = ?", String.class, email))
                .contains("Cancelled: Late Show").doesNotContain("Your Seatline tickets");
    }
}
