package dev.seatline.api.event;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.seatline.api.venue.VenueService;
import dev.seatline.api.web.ApiException;
import dev.seatline.api.web.CurrentUser;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class EventService {

    public enum TierKind { STANDARD, EARLY_BIRD, STUDENT, VIP }

    public record TierSpec(String name, TierKind kind, int priceCents, Instant endsAt, List<String> sections) {}

    public record Tier(UUID id, String name, TierKind kind, int priceCents, long allInCents, Instant endsAt,
                       List<String> sections) {}

    public record Event(UUID id, UUID venueId, UUID organizerId, String name, Instant startsAt, String status,
                        int perFanLimit, Pricing pricing, List<Tier> tiers) {

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

    public EventService(JdbcClient db, VenueService venues, Clock clock) {
        this.db = db;
        this.venues = venues;
        this.clock = clock;
    }

    @Transactional
    public UUID create(CurrentUser by, UUID venueId, String name, Instant startsAt, int perFanLimit, Pricing pricing,
                       List<TierSpec> tiers) {
        Map<String, UUID> sectionIds = new HashMap<>();
        for (VenueService.Section s : venues.get(venueId).sections()) {
            sectionIds.put(s.name(), s.id());
        }
        UUID eventId = UUID.randomUUID();
        db.sql("""
                insert into events (id, venue_id, organizer_id, name, starts_at, status, per_fan_limit,
                                    fee_bps, fee_flat_cents, tax_bps)
                values (?, ?, ?, ?, ?, 'DRAFT', ?, ?, ?, ?)""")
                .params(eventId, venueId, by.id(), name, Timestamp.from(startsAt), perFanLimit,
                        pricing.feeBps(), pricing.feeFlatCents(), pricing.taxBps())
                .update();
        for (TierSpec t : tiers) {
            UUID tierId = UUID.randomUUID();
            db.sql("insert into tiers (id, event_id, name, kind, price_cents, ends_at) values (?, ?, ?, ?, ?, ?)")
                    .params(tierId, eventId, t.name(), t.kind().name(), t.priceCents(),
                            t.endsAt() == null ? null : Timestamp.from(t.endsAt()))
                    .update();
            for (String section : t.sections()) {
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
                   Pricing pricing) {}
        Row row = db.sql("select * from events where id = ?").param(id)
                .query((rs, n) -> new Row(rs.getObject("venue_id", UUID.class), rs.getObject("organizer_id", UUID.class),
                        rs.getString("name"), rs.getTimestamp("starts_at").toInstant(), rs.getString("status"),
                        rs.getInt("per_fan_limit"),
                        new Pricing(rs.getInt("fee_bps"), rs.getInt("fee_flat_cents"), rs.getInt("tax_bps"))))
                .optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "no_such_event"));

        Instant now = clock.instant();
        List<Tier> tiers = new ArrayList<>();
        db.sql("select id, name, kind, price_cents, ends_at from tiers where event_id = ? order by price_cents").param(id)
                .query(rs -> {
                    Timestamp ends = rs.getTimestamp("ends_at");
                    if (ends != null && !ends.toInstant().isAfter(now)) {
                        return; // early bird is over
                    }
                    UUID tierId = rs.getObject("id", UUID.class);
                    List<String> sections = db.sql("""
                            select s.name from tier_sections ts join sections s on s.id = ts.section_id
                            where ts.tier_id = ? order by s.position""").param(tierId).query(String.class).list();
                    int price = rs.getInt("price_cents");
                    tiers.add(new Tier(tierId, rs.getString("name"), TierKind.valueOf(rs.getString("kind")), price,
                            row.pricing().price(price, 0).total(), ends == null ? null : ends.toInstant(), sections));
                });
        return new Event(id, row.venueId(), row.organizerId(), row.name(), row.startsAt(), row.status(), row.limit(),
                row.pricing(), tiers);
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
        db.sql("update events set status = 'ON_SALE' where id = ?").param(eventId).update();
    }

    @Transactional
    public void addPromo(UUID eventId, CurrentUser by, String code, int percentOff, int maxUses, Instant expiresAt) {
        requireOwner(eventId, by);
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

    /**
     * Prices seats for a fan before they hold them. Checks each seat is in a section the tier
     * covers, student prices go to student emails, and the promo code is live.
     */
    public Quote quote(UUID eventId, List<QuoteItem> items, String promo, String fanEmail) {
        Event e = get(eventId);
        if (!e.status().equals("ON_SALE")) {
            throw new ApiException(HttpStatus.CONFLICT, "not_on_sale");
        }
        if (items.size() > e.perFanLimit()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "over_limit");
        }
        int percentOff = promo == null || promo.isBlank() ? 0 : promoPercent(eventId, promo);

        Map<String, String> sectionOfSeat = new HashMap<>();
        db.sql("""
                select st.label, s.name from seats st join sections s on s.id = st.section_id
                where s.venue_id = ?""").param(e.venueId())
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
