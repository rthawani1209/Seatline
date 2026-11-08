package dev.seatline.loadtest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sets up a sale before the fans arrive and checks the books afterwards. Accounts are made the
 * way the e2e tests make them: through the API, with the confirmation code read from the email
 * outbox and roles set in Postgres. The fans are copies of one real sign-up (same password hash)
 * so the run doesn't spend minutes on 10,000 sign-up emails, and they all sign in through the API
 * before the sale opens, like fans waiting with the page open.
 */
final class Stage {

    static final String PASSWORD = "correct-horse-battery-staple";
    static final ObjectMapper JSON = new ObjectMapper();

    record Sale(String eventId, String tierId, String organizerToken, List<String> fanTokens, int seats) {}

    final String api;
    final String db;
    final String run = Long.toString(System.currentTimeMillis(), 36);
    final HttpClient http = HttpClient.newHttpClient();

    Stage(String api, String db) {
        this.api = api;
        this.db = db;
    }

    Sale prepare(int fans, int sections, int rows, int seatsPerRow) throws Exception {
        String organizer = account("organizer-" + run + "@load.test", "ORGANIZER");

        String template = "fan-" + run + "@load.test";
        account(template, "FAN");
        try (Connection c = connect();
                PreparedStatement copy = c.prepareStatement("""
                        insert into users (id, email, password_hash, display_name, role, email_verified)
                        select gen_random_uuid(), 'fan-' || ? || '-' || g || '@load.test', u.password_hash,
                               'Load fan ' || g, 'FAN', true
                        from users u, generate_series(1, ?) g where u.email = ?""")) {
            copy.setString(1, run);
            copy.setInt(2, fans);
            copy.setString(3, template);
            copy.executeUpdate();
        }
        List<String> tokens = signInAll(fans);

        List<Map<String, Object>> sectionList = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (int s = 1; s <= sections; s++) {
            List<Map<String, Object>> rowList = new ArrayList<>();
            for (int r = 0; r < rows; r++) {
                rowList.add(Map.of("label", rowLabel(r), "seats", seatsPerRow, "accessible", List.of(),
                        "limitedView", List.of()));
            }
            names.add("S" + s);
            sectionList.add(Map.of("name", "S" + s, "rows", rowList));
        }
        JsonNode venue = post("/venues", organizer, Map.of("name", "Load Test Arena", "city", "Testville",
                "sections", sectionList), 201);

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("venueId", venue.get("id").asText());
        event.put("name", "Sell-out " + run);
        event.put("startsAt", Instant.now().plus(30, ChronoUnit.DAYS).toString());
        event.put("perFanLimit", 4);
        event.put("feeBps", 1000);
        event.put("feeFlatCents", 150);
        event.put("taxBps", 800);
        event.put("tiers", List.of(Map.of("name", "General", "kind", "STANDARD", "priceCents", 5000,
                "sections", names)));
        String eventId = post("/events", organizer, event, 201).get("id").asText();
        post("/events/" + eventId + "/publish", organizer, null, 204);
        String tierId = get("/events/" + eventId, null).get("tiers").get(0).get("id").asText();

        return new Sale(eventId, tierId, organizer, tokens, sections * rows * seatsPerRow);
    }

    /**
     * Checks the books once they've settled. A payment the API couldn't finish in the rush (say the
     * database was too busy to write the order) is finished or refunded by the reconciler within a
     * couple of minutes, so this waits for that and says how long it took.
     */
    Map<String, Object> settledAudit(Sale sale) throws Exception {
        long start = System.nanoTime();
        Map<String, Object> out = audit(sale);
        out.put("paymentsUnfinishedWhenTheSaleEnded", out.get("paymentsLost"));
        out.put("ticketsWhenTheSaleEnded", out.get("tickets"));
        while (((Long) out.get("paymentsLost") > 0 || !out.get("tickets").equals(out.get("ledgerSold")))
                && System.nanoTime() - start < 300_000_000_000L) {
            Thread.sleep(5_000);
            Map<String, Object> again = audit(sale);
            again.put("paymentsUnfinishedWhenTheSaleEnded", out.get("paymentsUnfinishedWhenTheSaleEnded"));
            again.put("ticketsWhenTheSaleEnded", out.get("ticketsWhenTheSaleEnded"));
            out = again;
        }
        out.put("secondsForTheBooksToSettle", (System.nanoTime() - start) / 1_000_000_000);
        return out;
    }

    /** What actually happened, from Postgres and the ledger, written next to Gatling's own report. */
    Map<String, Object> audit(Sale sale) throws Exception {
        Map<String, Object> out = new LinkedHashMap<>();
        try (Connection c = connect()) {
            out.put("tickets", count(c, "select count(*) from tickets where event_id = ?::uuid", sale.eventId()));
            out.put("seatsWithTwoTickets", count(c, """
                    select count(*) from (select seat from tickets where event_id = ?::uuid and status = 'VALID'
                                          group by seat having count(*) > 1) x""", sale.eventId()));
            out.put("orders", count(c, "select count(*) from orders where event_id = ?::uuid", sale.eventId()));
            // every order must match a payment that went through, for the same amount
            out.put("ordersWithoutPayment", count(c, """
                    select count(*) from orders o where o.event_id = ?::uuid and not exists (
                        select 1 from payment_attempts p where p.hold_id = o.hold_id
                        and p.payment_ref = o.payment_ref and p.amount_cents = o.total_cents
                        and p.state = 'DONE')""", sale.eventId()));
            // and money taken for this sale either became an order or is being refunded
            out.put("paymentsLost", count(c, """
                    select count(*) from payment_attempts p join holds h on h.id = p.hold_id
                    where h.event_id = ?::uuid and p.state in ('CHARGING', 'CHARGED')""", sale.eventId()));
        }
        JsonNode stats = get("/events/" + sale.eventId() + "/stats", sale.organizerToken());
        out.put("ledgerSold", stats.get("sold").asLong());
        out.put("ledgerHeld", stats.get("held").asLong());
        out.put("ledgerFree", stats.get("free").asLong());
        out.put("revenueCents", stats.get("revenueCents").asLong());
        return out;
    }

    // ---- accounts

    private String account(String email, String role) throws Exception {
        post("/auth/register", null, Map.of("email", email, "password", PASSWORD, "displayName", "Load " + role), 201);
        post("/auth/verify", null, Map.of("token", confirmationCode(email)), 204);
        if (!role.equals("FAN")) {
            try (Connection c = connect();
                    PreparedStatement p = c.prepareStatement("update users set role = ? where email = ?")) {
                p.setString(1, role);
                p.setString(2, email);
                p.executeUpdate();
            }
        }
        return signIn(email);
    }

    private String signIn(String email) throws IOException, InterruptedException {
        return post("/auth/login", null, Map.of("email", email, "password", PASSWORD), 200).get("token").asText();
    }

    private List<String> signInAll(int fans) throws Exception {
        // password checks are deliberately slow (Argon2), so a few at a time is as fast as it gets
        try (ExecutorService pool = Executors.newFixedThreadPool(16)) {
            List<Future<String>> futures = new ArrayList<>();
            for (int g = 1; g <= fans; g++) {
                String email = "fan-" + run + "-" + g + "@load.test";
                futures.add(pool.submit(() -> signIn(email)));
            }
            List<String> tokens = new ArrayList<>(fans);
            for (Future<String> f : futures) {
                tokens.add(f.get());
            }
            return tokens;
        }
    }

    private String confirmationCode(String email) throws SQLException {
        try (Connection c = connect();
                PreparedStatement p = c.prepareStatement(
                        "select body from outbox_emails where to_address = ? order by id desc limit 1")) {
            p.setString(1, email);
            try (ResultSet r = p.executeQuery()) {
                Matcher m = r.next() ? Pattern.compile("code: (\\S+)").matcher(r.getString(1)) : null;
                if (m == null || !m.find()) {
                    throw new IllegalStateException("no confirmation email for " + email);
                }
                return m.group(1);
            }
        }
    }

    // ---- plumbing

    static String rowLabel(int r) {
        return r < 26 ? String.valueOf((char) ('A' + r)) : "" + (char) ('A' + r / 26 - 1) + (char) ('A' + r % 26);
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(db, "seatline", "seatline");
    }

    private static long count(Connection c, String sql, String eventId) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(sql)) {
            p.setString(1, eventId);
            try (ResultSet r = p.executeQuery()) {
                r.next();
                return r.getLong(1);
            }
        }
    }

    private JsonNode get(String path, String token) throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder(URI.create(api + path)).GET(), token, 200);
    }

    private JsonNode post(String path, String token, Object body, int expect) throws IOException, InterruptedException {
        HttpRequest.BodyPublisher b = body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body));
        return send(HttpRequest.newBuilder(URI.create(api + path)).POST(b).header("content-type", "application/json"),
                token, expect);
    }

    private JsonNode send(HttpRequest.Builder req, String token, int expect) throws IOException, InterruptedException {
        if (token != null) {
            req.header("authorization", "Bearer " + token);
        }
        HttpResponse<String> res = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != expect) {
            throw new IllegalStateException(req.build().uri() + " gave " + res.statusCode() + ": " + res.body());
        }
        return res.body().isEmpty() ? JSON.nullNode() : JSON.readTree(res.body());
    }
}
