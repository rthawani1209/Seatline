package dev.seatline.api.organizer;

import dev.seatline.api.event.EventService;
import dev.seatline.api.queue.WaitingRoom;
import dev.seatline.api.seats.Seats;
import dev.seatline.api.web.CurrentUser;
import dev.seatline.ledger.seats.SeatLedger.SeatStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** What the organizer dashboard shows: their events, and live sales numbers for one. */
@RestController
class OrganizerController {

    /**
     * @param seats every seat on sale; free + held + sold = seats
     * @param revenueCents what fans paid for tickets still valid (returns are refunded)
     * @param scanned tickets let in at the door
     * @param waiting fans in the waiting room right now
     */
    record Stats(int seats, int free, int held, int sold, long orders, long tickets, long revenueCents,
                 long returned, long scanned, long waiting) {}

    private final EventService events;
    private final Seats seats;
    private final WaitingRoom room;
    private final JdbcClient db;

    OrganizerController(EventService events, Seats seats, WaitingRoom room, JdbcClient db) {
        this.events = events;
        this.seats = seats;
        this.room = room;
        this.db = db;
    }

    /** The caller's events, drafts too (an admin sees everyone's), soonest first. */
    @GetMapping("/organizer/events")
    List<EventService.Event> mine(@AuthenticationPrincipal Jwt jwt) {
        CurrentUser me = CurrentUser.of(jwt);
        List<UUID> ids = me.isAdmin()
                ? db.sql("select id from events order by starts_at").query(UUID.class).list()
                : db.sql("select id from events where organizer_id = ? order by starts_at").param(me.id())
                        .query(UUID.class).list();
        return ids.stream().map(events::get).toList();
    }

    @GetMapping("/events/{id}/stats")
    Stats stats(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        EventService.Event e = events.requireOwner(id, CurrentUser.of(jwt));
        Map<String, SeatStatus> live = e.status().equals("DRAFT") ? null : seats.statuses(id.toString());
        int free = 0;
        int held = 0;
        int sold = 0;
        // holds on a cancelled event can never be paid for, so they don't count as held
        boolean cancelled = e.status().equals("CANCELLED");
        if (live != null) {
            for (SeatStatus s : live.values()) {
                switch (s) {
                    case FREE -> free++;
                    case HELD -> { if (cancelled) free++; else held++; }
                    case SOLD -> sold++;
                }
            }
        }
        record Sales(long orders, long tickets, long revenue, long returned, long scanned) {}
        Sales sales = db.sql("""
                select (select count(*) from orders where event_id = :e),
                       count(*) filter (where status = 'VALID'),
                       coalesce(sum(price_cents) filter (where status = 'VALID'), 0),
                       count(*) filter (where status = 'RETURNED'),
                       count(*) filter (where scanned_at is not null)
                from tickets where event_id = :e""")
                .param("e", id)
                .query((rs, n) -> new Sales(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5)))
                .single();
        return new Stats(free + held + sold, free, held, sold, sales.orders(), sales.tickets(), sales.revenue(),
                sales.returned(), sales.scanned(), room.waiting(e));
    }
}
