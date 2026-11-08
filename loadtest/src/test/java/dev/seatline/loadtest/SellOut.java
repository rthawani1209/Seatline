package dev.seatline.loadtest;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.asLongAs;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.jmesPath;
import static io.gatling.javaapi.core.CoreDsl.pause;
import static io.gatling.javaapi.core.CoreDsl.responseTimeInMillis;
import static io.gatling.javaapi.core.CoreDsl.rampUsers;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Session;
import io.gatling.javaapi.core.Simulation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * "Simulate a sell-out": far more fans than seats arrive within a minute. Each one loads the live
 * seat map, grabs one or two free seats, and pays. If someone beat them to a seat they reload the
 * map and try again, up to five times. A "busy, try again" (503) is retried after a second. Afterwards {@link Stage#audit} checks the books: no seat
 * with two tickets, the ledger's sold count matching the tickets, and every order paid for.
 *
 * Settings (-D): fans (10000), rampSeconds (60), sections x rows x seatsPerRow (10 x 25 x 20),
 * api (http://localhost:8080), db (jdbc:postgresql://localhost:5432/seatline), audit (where the
 * book check is written, target/sellout-audit.json).
 */
public class SellOut extends Simulation {

    static final int FANS = Integer.getInteger("fans", 10_000);
    static final int RAMP = Integer.getInteger("rampSeconds", 60);
    static final String API = System.getProperty("api", "http://localhost:8080");
    static final String DB = System.getProperty("db", "jdbc:postgresql://localhost:5432/seatline");

    final Stage stage = new Stage(API, DB);
    final Stage.Sale sale;

    // how each fan's visit ended
    final AtomicInteger bought = new AtomicInteger();
    final AtomicInteger gaveUp = new AtomicInteger();
    final AtomicInteger soldOut = new AtomicInteger();
    final AtomicInteger paymentFailed = new AtomicInteger();
    final AtomicInteger seatsBought = new AtomicInteger();
    final AtomicInteger busyAnswers = new AtomicInteger();

    // raw response times, so the results file has exact percentiles rather than numbers read off the report
    final Map<String, ConcurrentLinkedQueue<Long>> times = new ConcurrentHashMap<>();
    volatile long firstArrival;
    volatile long lastPurchase;

    public SellOut() throws Exception {
        sale = stage.prepare(FANS, Integer.getInteger("sections", 10), Integer.getInteger("rows", 25),
                Integer.getInteger("seatsPerRow", 20));
        System.out.printf("sale %s ready: %d seats, %d fans signed in%n", sale.eventId(), sale.seats(), FANS);

        Iterator<Map<String, Object>> fans = sale.fanTokens().stream()
                .map(t -> Map.<String, Object>of("token", t)).iterator();

        // A busy server answers 503 "try again"; fans do, after a second, like pressing the button again.
        ChainBuilder tryToHold = exec(http("seat map").get("/events/" + sale.eventId() + "/seats")
                        .check(status().in(200, 503).saveAs("mapStatus"))
                        .check(jmesPath("[?status=='FREE'].seat").ofList().optional().saveAs("free"))
                        .check(responseTimeInMillis().saveAs("ms")))
                .exec(s -> timed("seat map", s))
                .exec(this::pickSeats)
                .doIf(s -> s.getInt("mapStatus") == 200 && !s.getBoolean("soldOut")).then(
                        exec(http("hold").post("/events/" + sale.eventId() + "/holds")
                                .header("authorization", "Bearer #{token}")
                                .body(StringBody("#{holdBody}")).asJson()
                                // 409 is the expected answer when someone else got the seat first
                                .check(status().in(201, 409, 503).saveAs("holdStatus"))
                                .check(jmesPath("holdId").optional().saveAs("holdId"))
                                .check(responseTimeInMillis().saveAs("ms")))
                        .exec(s -> timed("hold", s)))
                .exec(s -> busy(s, s.getInt("mapStatus") == 503 || (s.contains("holdStatus") && s.getInt("holdStatus") == 503)))
                .doIf(s -> s.getBoolean("wasBusy")).then(pause(1))
                .exec(s -> s.set("tries", s.getInt("tries") + 1).remove("holdStatus"));

        ChainBuilder pay = exec(http("checkout").post("/holds/#{holdId}/checkout")
                        .header("authorization", "Bearer #{token}")
                        .body(StringBody("{\"cardToken\":\"tok_visa\"}")).asJson()
                        .check(status().in(200, 503).saveAs("payStatus"))
                        .check(jmesPath("tickets").ofList().optional().saveAs("tickets"))
                        .check(responseTimeInMillis().saveAs("ms")))
                .exec(s -> timed("checkout", s))
                .exec(s -> busy(s, s.getInt("payStatus") == 503).set("payTries", s.getInt("payTries") + 1))
                .doIf(s -> s.getBoolean("wasBusy")).then(pause(1));

        ScenarioBuilder fan = scenario("fan")
                .feed(fans)
                .exec(s -> arrive(s).set("tries", 0).set("payTries", 0).set("soldOut", false))
                .asLongAs(s -> !s.contains("holdId") && !s.getBoolean("soldOut") && s.getInt("tries") < 5)
                .on(tryToHold)
                .doIf(s -> s.contains("holdId")).then(
                        asLongAs(s -> !s.contains("tickets") && s.getInt("payTries") < 5).on(pay))
                .exec(this::countOutcome);

        setUp(fan.injectOpen(rampUsers(FANS).during(RAMP)))
                .protocols(http.baseUrl(API).acceptHeader("application/json").shareConnections());
    }

    private Session pickSeats(Session s) {
        if (s.getInt("mapStatus") != 200) {
            return s;
        }
        List<String> free = s.contains("free") ? new ArrayList<>(s.getList("free")) : List.of();
        if (free.isEmpty()) {
            return s.set("soldOut", true);
        }
        int want = Math.min(free.size(), ThreadLocalRandom.current().nextInt(1, 3));
        Collections.shuffle(free);
        StringBuilder items = new StringBuilder();
        for (String seat : free.subList(0, want)) {
            items.append(items.isEmpty() ? "" : ",")
                    .append("{\"seat\":\"").append(seat).append("\",\"tierId\":\"").append(sale.tierId()).append("\"}");
        }
        return s.remove("free").set("want", want).set("holdBody", "{\"items\":[" + items + "]}");
    }

    private Session arrive(Session s) {
        long now = System.currentTimeMillis();
        synchronized (this) {
            if (firstArrival == 0) {
                firstArrival = now;
            }
        }
        return s.set("arrived", now);
    }

    private Session timed(String name, Session s) {
        if (s.contains("ms")) {
            times.computeIfAbsent(name, k -> new ConcurrentLinkedQueue<>()).add(s.getLong("ms"));
        }
        return s.remove("ms");
    }

    private Session busy(Session s, boolean wasBusy) {
        if (wasBusy) {
            busyAnswers.incrementAndGet();
        }
        return s.set("wasBusy", wasBusy);
    }

    private Session countOutcome(Session s) {
        if (s.contains("tickets")) {
            long now = System.currentTimeMillis();
            bought.incrementAndGet();
            seatsBought.addAndGet(s.getList("tickets").size());
            // the whole purchase as the fan saw it: first look at the map to paid, retries included
            times.computeIfAbsent("purchase", k -> new ConcurrentLinkedQueue<>()).add(now - s.getLong("arrived"));
            synchronized (this) {
                lastPurchase = Math.max(lastPurchase, now);
            }
        } else if (s.contains("holdId")) {
            paymentFailed.incrementAndGet();
        } else if (s.getBoolean("soldOut")) {
            soldOut.incrementAndGet();
        } else {
            gaveUp.incrementAndGet();
        }
        return s;
    }

    @Override
    public void after() {
        try {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("eventId", sale.eventId());
            out.put("fans", FANS);
            out.put("rampSeconds", RAMP);
            out.put("seats", sale.seats());
            out.put("fansWhoBought", bought.get());
            out.put("seatsBoughtByFans", seatsBought.get());
            out.put("fansWhoFoundItSoldOut", soldOut.get());
            out.put("fansWhoGaveUpAfterFiveTries", gaveUp.get());
            out.put("fansWhosePaymentFailed", paymentFailed.get());
            out.put("busyAnswers503", busyAnswers.get());
            double seconds = (lastPurchase - firstArrival) / 1000.0;
            out.put("secondsToSellOut", Math.round(seconds * 10) / 10.0);
            out.put("purchasesPerSecond", Math.round(bought.get() / seconds * 10) / 10.0);
            Map<String, Object> latency = new LinkedHashMap<>();
            for (String name : List.of("seat map", "hold", "checkout", "purchase")) {
                latency.put(name, percentiles(times.getOrDefault(name, new ConcurrentLinkedQueue<>())));
            }
            out.put("latencyMs", latency);
            out.putAll(stage.settledAudit(sale));
            String json = Stage.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(out);
            Path file = Path.of(System.getProperty("audit", "target/sellout-audit.json")).toAbsolutePath();
            Files.createDirectories(file.getParent());
            Files.writeString(file, json);
            System.out.println(json);
        } catch (Exception e) {
            throw new IllegalStateException("couldn't check the books after the sale", e);
        }
    }

    static Map<String, Object> percentiles(ConcurrentLinkedQueue<Long> samples) {
        long[] v = samples.stream().mapToLong(Long::longValue).sorted().toArray();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", v.length);
        if (v.length > 0) {
            out.put("p50", v[(int) Math.ceil(0.50 * v.length) - 1]);
            out.put("p95", v[(int) Math.ceil(0.95 * v.length) - 1]);
            out.put("p99", v[(int) Math.ceil(0.99 * v.length) - 1]);
            out.put("max", v[v.length - 1]);
        }
        return out;
    }
}
