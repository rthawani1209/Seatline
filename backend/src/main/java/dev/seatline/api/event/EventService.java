package dev.seatline.api.event;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.seatline.api.seats.Seats;
import dev.seatline.api.venue.VenueService;
import dev.seatline.ledger.seats.SeatCommand;
import dev.seatline.ledger.seats.SeatLedger.SeatStatus;
import dev.seatline.ledger.seats.SeatResult;
import dev.seatline.api.web.ApiException;
import dev.seatline.api.web.CurrentUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class EventService {

    public enum TierKind { STANDARD, EARLY_BIRD, STUDENT, VIP }

    public record TierSpec(String name, TierKind kind, int priceCents, Instant endsAt, List<String> sections) {}

    public record Tier(UUID id, String name, TierKind kind, int priceCents, long allInCents, Instant endsAt,
                       List<String> sections) {}

    /**
     * @param queuePerMinute fans let in from the waiting room per minute, or null for no waiting room
     * @param onSaleAt when sales open (the waiting room starts letting fans in from then); set
     *     by the organizer or, if not, when the event is published
     */
    public record Event(UUID id, UUID venueId, UUID organizerId, String name, Instant startsAt, String status,
                        int perFanLimit, Pricing pricing, Integer queuePerMinute, Instant onSaleAt, List<Tier> tiers) {

        /** Cheapest all-in price currently on offer, for listings ("from $42"). */
        @JsonProperty
        public Long fromCents() {
            return tiers.stream().map(Tier::allInCents).min(Long::compare).orElse(null);
        }
    }

    public record QuoteItem(String seat, UUID tierId) {}

    public record Quote(List<QuoteLine> lines, long totalCents, String promoCode) {}

    public record QuoteLine(String seat, String tier, Pricing.Line price) {}

    private final JdbcClient db;
    private final VenueService venues;
    private final Clock clock;
    private final Seats seats;
    private final long seatMapFreshNanos;
    private final Map<UUID, CompletableFuture<SeatMap>> seatMaps = new ConcurrentHashMap<>();

    public EventService(JdbcClient db, VenueService venues, Clock clock, Seats seats,
                        @Value("${seatline.seat-map-cache-ms:500}") long seatMapCacheMillis) {
        this.db = db;
        this.venues = venues;
        this.clock = clock;
        this.seats = seats;
        this.seatMapFreshNanos = seatMapCacheMillis * 1_000_000;
    }

    @Transactional
    public UUID create(CurrentUser by, UUID venueId, String name, Instant startsAt, int perFanLimit, Pricing pricing,
                       Integer queuePerMinute, Instant onSaleAt, List<TierSpec> tiers) {
        if (onSaleAt != null && !onSaleAt.isBefore(startsAt)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "on_sale_after_start");
        }
        Map<String, UUID> sectionIds = new HashMap<>();
        for (VenueService.Section s : venues.get(venueId).sections()) {
            sectionIds.put(s.name(), s.id());
        }
        UUID eventId = UUID.randomUUID();
        db.sql("""
                insert into events (id, venue_id, organizer_id, name, starts_at, status, per_fan_limit,
                                    fee_bps, fee_flat_cents, tax_bps, queue_per_minute, on_sale_at)
                values (?, ?, ?, ?, ?, 'DRAFT', ?, ?, ?, ?, ?, ?)""")
                .params(eventId, venueId, by.id(), name, Timestamp.from(startsAt), perFanLimit,
                        pricing.feeBps(), pricing.feeFlatCents(), pricing.taxBps(), queuePerMinute,
                        onSaleAt == null ? null : Timestamp.from(onSaleAt))
                .update();
        for (TierSpec t : tiers) {
            UUID tierId = UUID.randomUUID();
            db.sql("insert into tiers (id, event_id, name, kind, price_cents, ends_at) values (?, ?, ?, ?, ?, ?)")
                    .params(tierId, eventId, t.name(), t.kind().name(), t.priceCents(),
                            t.endsAt() == null ? null : Timestamp.from(t.endsAt()))
                    .update();
            for (String section : new LinkedHashSet<>(t.sections())) { // naming a section twice is harmless
                UUID sectionId = sectionIds.get(section);
                if (sectionId == null) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "no_such_section");
                }
                db.sql("insert into tier_sections (tier_id, section_id) values (?, ?)").params(tierId, sectionId).update();
            }
        }
        return eventId;
    }

    public List<Event> listOnSale() {
        List<UUID> ids = db.sql("select id from events where status = 'ON_SALE' and starts_at > ? order by starts_at")
                .param(Timestamp.from(clock.instant())).query(UUID.class).list();
        return ids.stream().map(this::get).toList();
    }

    /** Drafts are only visible to their organizer and admins. */
    public Event getVisible(UUID id, CurrentUser viewer) {
        Event e = get(id);
        if (e.status().equals("DRAFT") && (viewer == null || !(viewer.isAdmin() || viewer.id().equals(e.organizerId())))) {
            throw new ApiException(HttpStatus.NOT_FOUND, "no_such_event");
        }
        return e;
    }

    public Event get(UUID id) {
        record Row(UUID venueId, UUID organizerId, String name, Instant startsAt, String status, int limit,
                   Pricing pricing, Integer queuePerMinute, Instant onSaleAt) {}
        Row row = db.sql("select * from events where id = ?").param(id)
                .query((rs, n) -> new Row(rs.getObject("venue_id", UUID.class), rs.getObject("organizer_id", UUID.class),
                        rs.getString("name"), rs.getTimestamp("starts_at").toInstant(), rs.getString("status"),
                        rs.getInt("per_fan_limit"),
                        new Pricing(rs.getInt("fee_bps"), rs.getInt("fee_flat_cents"), rs.getInt("tax_bps")),
                        rs.getObject("queue_per_minute", Integer.class),
                        rs.getTimestamp("on_sale_at") == null ? null : rs.getTimestamp("on_sale_at").toInstant()))
                .optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "no_such_event"));

        Instant now = clock.instant();
        List<Tier> tiers = new ArrayList<>();
        // one query: a query inside another's row callback needs a second connection while holding
        // the first, which deadlocks the pool under load (found by the 20-fan race test)
        db.sql("""
                select t.id, t.name, t.kind, t.price_cents, t.ends_at,
                       array(select s.name from tier_sections ts join sections s on s.id = ts.section_id
                             where ts.tier_id = t.id order by s.position) as sections
                from tiers t where t.event_id = ? order by t.price_cents""").param(id)
                .query(rs -> {
                    Timestamp ends = rs.getTimestamp("ends_at");
                    if (ends != null && !ends.toInstant().isAfter(now)) {
                        return; // early bird is over
                    }
                    List<String> sections = List.of((String[]) rs.getArray("sections").getArray());
                    int price = rs.getInt("price_cents");
                    tiers.add(new Tier(rs.getObject("id", UUID.class), rs.getString("name"),
                            TierKind.valueOf(rs.getString("kind")), price, row.pricing().price(price, 0).total(),
                            ends == null ? null : ends.toInstant(), sections));
                });
        return new Event(id, row.venueId(), row.organizerId(), row.name(), row.startsAt(), row.status(), row.limit(),
                row.pricing(), row.queuePerMinute(), row.onSaleAt(), tiers);
    }

    /** Organizers can change only their own events; admins can change any. */
    public Event requireOwner(UUID id, CurrentUser by) {
        Event e = get(id);
        if (!by.isAdmin() && !by.id().equals(e.organizerId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "not_your_event");
        }
        return e;
    }

    @Transactional
    public void publish(UUID eventId, CurrentUser by) {
        Event e = requireOwner(eventId, by);
        if (!e.status().equals("DRAFT")) {
            throw new ApiException(HttpStatus.CONFLICT, "already_published");
        }
        if (e.tiers().isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "no_tiers");
        }
        // the ledger first: once it has the event, seats can be held, so only then go on sale.
        // A retry after a crash between the two steps finds the event already there, which is fine.
        List<String> sellable = db.sql("""
                select distinct st.label from seats st
                join tier_sections ts on ts.section_id = st.section_id
                join tiers t on t.id = ts.tier_id
                where t.event_id = ? order by st.label""").param(eventId).query(String.class).list();
        SeatResult created = seats.write(new SeatCommand.CreateEvent(eventId.toString(), sellable, e.perFanLimit()));
        if (!created.ok() && created.outcome() != SeatResult.Outcome.ALREADY_EXISTS) {
            throw new IllegalStateException("ledger refused event " + eventId + ": " + created.outcome());
        }
        // published events are listed straight away; sales start at the scheduled time, or now
        db.sql("update events set status = 'ON_SALE', on_sale_at = coalesce(on_sale_at, now()) where id = ?")
                .param(eventId).update();
    }

    public record SeatState(String seat, SeatStatus status) {}

    /** One read of an event's seats; null seats means a draft, which only its organizer may see. */
    private record SeatMap(long readAt, List<SeatState> seats) {}

    /**
     * Live status of every seat on sale for an event, from the ledger. In a big sale thousands of
     * fans poll the same map, so one ledger read serves every request that arrives while it's
     * running or in the half second after (seatline.seat-map-cache-ms). That can only show a seat
     * as free a moment after someone took it; holding goes to the ledger, which says it's gone.
     */
    public List<SeatState> seatStates(UUID eventId, CurrentUser viewer) {
        while (true) {
            CompletableFuture<SeatMap> current = seatMaps.get(eventId);
            if (current != null && !(current.isDone() && stale(current))) {
                SeatMap map;
                try {
                    map = current.join();
                } catch (java.util.concurrent.CompletionException e) {
                    // a failed read fails everyone who was waiting on it, the same as their own would have
                    throw e.getCause() instanceof RuntimeException r ? r : e;
                }
                return map.seats() != null ? map.seats() : draftSeats(eventId, viewer);
            }
            CompletableFuture<SeatMap> mine = new CompletableFuture<>();
            boolean ours = current == null ? seatMaps.putIfAbsent(eventId, mine) == null
                    : seatMaps.replace(eventId, current, mine);
            if (!ours) {
                continue; // someone else just started a read; wait for theirs
            }
            try {
                Event e = get(eventId);
                SeatMap map = new SeatMap(System.nanoTime(), e.status().equals("DRAFT") ? null : read(eventId));
                mine.complete(map);
                if (map.seats() == null) {
                    seatMaps.remove(eventId, mine); // a draft is about to be published; don't keep that answer
                }
                return map.seats() != null ? map.seats() : draftSeats(eventId, viewer);
            } catch (RuntimeException failed) {
                seatMaps.remove(eventId, mine);
                mine.completeExceptionally(failed);
                throw failed;
            }
        }
    }

    private boolean stale(CompletableFuture<SeatMap> read) {
        return read.isCompletedExceptionally() || System.nanoTime() - read.join().readAt() >= seatMapFreshNanos;
    }

    private List<SeatState> draftSeats(UUID eventId, CurrentUser viewer) {
        getVisible(eventId, viewer); // 404 unless it's the organizer's own draft
        return List.of();
    }

    private List<SeatState> read(UUID eventId) {
        Map<String, SeatStatus> live = seats.statuses(eventId.toString());
        return live == null ? List.of() : live.entrySet().stream().map(s -> new SeatState(s.getKey(), s.getValue())).toList();
    }

    @Transactional
    public void addPromo(UUID eventId, CurrentUser by, String code, int percentOff, int maxUses, Instant expiresAt) {
        if (requireOwner(eventId, by).status().equals("CANCELLED")) {
            throw new ApiException(HttpStatus.CONFLICT, "event_cancelled");
        }
        int inserted = db.sql("""
                insert into promo_codes (id, event_id, code, percent_off, max_uses, expires_at)
                values (?, ?, ?, ?, ?, ?) on conflict (event_id, code) do nothing""")
                .params(UUID.randomUUID(), eventId, code.toUpperCase(Locale.ROOT), percentOff, maxUses,
                        expiresAt == null ? null : Timestamp.from(expiresAt))
                .update();
        if (inserted == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "code_exists");
        }
    }

    public record Promo(String code, int percentOff, int maxUses, int used, Instant expiresAt) {}

    public List<Promo> promos(UUID eventId, CurrentUser by) {
        requireOwner(eventId, by);
        return db.sql("select code, percent_off, max_uses, used, expires_at from promo_codes where event_id = ? order by code")
                .param(eventId)
                .query((rs, n) -> new Promo(rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getInt(4),
                        rs.getTimestamp(5) == null ? null : rs.getTimestamp(5).toInstant()))
                .list();
    }

    /**
     * Prices seats for a fan before they hold them. Checks each seat is in a section the tier
     * covers, student prices go to student emails, and the promo code is live.
     */
    public Quote quote(UUID eventId, List<QuoteItem> items, String promo, String fanEmail) {
        return quote(get(eventId), items, promo, fanEmail);
    }

    /** For callers that already read the event (holding seats does), to save a lookup per hold. */
    public Quote quote(Event e, List<QuoteItem> items, String promo, String fanEmail) {
        UUID eventId = e.id();
        if (!e.status().equals("ON_SALE") || clock.instant().isBefore(e.onSaleAt())) {
            throw new ApiException(HttpStatus.CONFLICT, "not_on_sale");
        }
        if (!clock.instant().isBefore(e.startsAt())) {
            throw new ApiException(HttpStatus.CONFLICT, "event_started");
        }
        if (items.size() > e.perFanLimit()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "over_limit");
        }
        int percentOff = promo == null || promo.isBlank() ? 0 : promoPercent(eventId, promo);

        // only the seats asked for: in a 5,000-seat venue, loading the whole map on every hold was most
        // of the database's work during a sell-out
        Map<String, String> sectionOfSeat = new HashMap<>();
        db.sql("""
                select st.label, s.name from seats st join sections s on s.id = st.section_id
                where s.venue_id = ? and st.label = any(?)""")
                .params(e.venueId(), items.stream().map(QuoteItem::seat).toArray(String[]::new))
                .query(rs -> {
                    sectionOfSeat.put(rs.getString(1), rs.getString(2));
                });

        List<QuoteLine> lines = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        long total = 0;
        for (QuoteItem item : items) {
            if (!seen.add(item.seat())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "duplicate_seat");
            }
            String section = sectionOfSeat.get(item.seat());
            Tier tier = e.tiers().stream().filter(t -> t.id().equals(item.tierId())).findFirst()
                    .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "no_such_tier"));
            if (section == null || !tier.sections().contains(section)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "seat_not_in_tier");
            }
            if (tier.kind() == TierKind.STUDENT && !fanEmail.endsWith(".edu")) {
                throw new ApiException(HttpStatus.FORBIDDEN, "student_email_required");
            }
            Pricing.Line price = e.pricing().price(tier.priceCents(), percentOff);
            lines.add(new QuoteLine(item.seat(), tier.name(), price));
            total += price.total();
        }
        return new Quote(lines, total, percentOff > 0 ? promo.toUpperCase(Locale.ROOT) : null);
    }

    private int promoPercent(UUID eventId, String code) {
        return db.sql("""
                select percent_off from promo_codes
                where event_id = ? and code = ? and used < max_uses and (expires_at is null or expires_at > ?)""")
                .params(eventId, code.toUpperCase(Locale.ROOT), Timestamp.from(clock.instant()))
                .query(Integer.class).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "invalid_promo_code"));
    }
}
