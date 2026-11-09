package dev.seatline.api.ticket;

import dev.seatline.api.account.Role;
import dev.seatline.api.event.EventService;
import dev.seatline.api.seats.Seats;
import dev.seatline.api.web.ApiException;
import dev.seatline.api.web.CurrentUser;
import dev.seatline.ledger.seats.SeatLedger;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** QR codes for fans, checking them at the door, and who may check them at which event. */
@Service
public class DoorService {

    /**
     * OLD_CODE: from before a transfer. EXPIRED: right ticket, but a code more than ~15 s old (a
     * screenshot). CANCELLED: the event was called off and the ticket refunded.
     */
    public enum Result { OK, ALREADY_SCANNED, WRONG_EVENT, OLD_CODE, EXPIRED, CANCELLED, INVALID }

    public record Scan(Result result, String seat, String tier, String eventName, Instant scannedAt) {
        static Scan of(Result r) {
            return new Scan(r, null, null, null, null);
        }
    }

    public record StaffMember(UUID id, String email, String displayName) {}

    public record DoorEvent(UUID id, String name, Instant startsAt) {}

    private final JdbcClient db;
    private final Seats seats;
    private final TicketCodes codes;
    private final EventService events;
    private final Clock clock;

    public DoorService(JdbcClient db, Seats seats, TicketCodes codes, EventService events, Clock clock) {
        this.db = db;
        this.seats = seats;
        this.codes = codes;
        this.events = events;
        this.clock = clock;
    }

    // ---- the door team for each event

    public List<StaffMember> staff(UUID eventId, CurrentUser by) {
        events.requireOwner(eventId, by);
        return db.sql("""
                select u.id, u.email, u.display_name from door_staff d join users u on u.id = d.user_id
                where d.event_id = ? order by d.added_at""").param(eventId)
                .query((rs, n) -> new StaffMember(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)))
                .list();
    }

    /** Adds someone with a door staff account to an event's door team. Adding them twice is fine. */
    public StaffMember addStaff(UUID eventId, CurrentUser by, String email) {
        events.requireOwner(eventId, by);
        record Found(UUID id, String email, String name, String role) {}
        Found u = db.sql("select id, email, display_name, role from users where email = lower(trim(?))").param(email)
                .query((rs, n) -> new Found(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4)))
                .optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "no_such_user"));
        if (!Role.DOOR_STAFF.name().equals(u.role())) {
            throw new ApiException(HttpStatus.CONFLICT, "not_door_staff");
        }
        db.sql("insert into door_staff (event_id, user_id) values (?, ?) on conflict do nothing")
                .params(eventId, u.id()).update();
        return new StaffMember(u.id(), u.email(), u.name());
    }

    public void removeStaff(UUID eventId, CurrentUser by, UUID userId) {
        events.requireOwner(eventId, by);
        db.sql("delete from door_staff where event_id = ? and user_id = ?").params(eventId, userId).update();
    }

    /** The events this person can scan at: the ones they're on the door team for (admins: all of them). */
    public List<DoorEvent> doorEvents(CurrentUser by) {
        var query = by.isAdmin()
                ? db.sql("select id, name, starts_at from events where status not in ('DRAFT', 'CANCELLED') order by starts_at")
                : db.sql("""
                        select e.id, e.name, e.starts_at from events e join door_staff d on d.event_id = e.id
                        where d.user_id = ? and e.status not in ('DRAFT', 'CANCELLED') order by e.starts_at""").param(by.id());
        return query
                .query((rs, n) -> new DoorEvent(rs.getObject(1, UUID.class), rs.getString(2),
                        rs.getTimestamp(3).toInstant()))
                .list();
    }

    private boolean onDoorTeam(UUID eventId, CurrentUser by) {
        return by.isAdmin() || db.sql("select exists (select 1 from door_staff where event_id = ? and user_id = ?)")
                .params(eventId, by.id()).query(Boolean.class).single();
    }

    // ---- codes

    public String qrCode(String ticketId, CurrentUser fan) {
        Integer version = db.sql("select version from tickets where id = ? and owner_id = ? and status = 'VALID'")
                .params(ticketId, fan.id()).query(Integer.class).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "no_such_ticket"));
        return codes.code(ticketId, version, clock.instant());
    }

    /**
     * Checks a code at the door. The ledger has the final word on whether the ticket exists and
     * which version is current; Postgres records the scan, and only the first scan wins.
     */
    public Scan scan(String code, UUID atEvent, CurrentUser by) {
        if (!onDoorTeam(atEvent, by)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "not_on_door_team");
        }
        TicketCodes.Parsed p = codes.parse(code);
        if (p == null) {
            return Scan.of(Result.INVALID);
        }
        Optional<SeatLedger.Ticket> live = seats.ticket(p.ticketId());
        if (live.isEmpty()) {
            return Scan.of(Result.INVALID); // returned, or never sold
        }
        if (live.get().version() != p.version()) {
            return Scan.of(Result.OLD_CODE); // transferred since this code was made
        }
        if (Math.abs(TicketCodes.step(clock.instant()) - p.step()) > 1) {
            return Scan.of(Result.EXPIRED);
        }
        record Row(UUID eventId, String seat, String tier, String eventName, Instant scannedAt, String status) {}
        Row t = db.sql("""
                select t.event_id, t.seat, t.tier, e.name, t.scanned_at, t.status
                from tickets t join events e on e.id = t.event_id where t.id = ?""").param(p.ticketId())
                .query((rs, n) -> new Row(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getTimestamp(5) == null ? null : rs.getTimestamp(5).toInstant(), rs.getString(6)))
                .optional().orElse(null);
        if (t == null || t.status().equals("RETURNED")) {
            return Scan.of(Result.INVALID);
        }
        if (t.status().equals("CANCELLED")) {
            return new Scan(Result.CANCELLED, t.seat(), t.tier(), t.eventName(), null);
        }
        if (!atEvent.equals(t.eventId())) {
            return new Scan(Result.WRONG_EVENT, t.seat(), t.tier(), t.eventName(), null);
        }
        // The update only matches once, so two doors scanning the same code can't both let it in,
        // and only while the ticket is still valid, so a return or cancel that just landed wins.
        Instant now = db.sql("""
                update tickets set scanned_at = now() where id = ? and scanned_at is null and status = 'VALID'
                returning scanned_at""")
                .param(p.ticketId()).query(Timestamp.class).optional().map(Timestamp::toInstant).orElse(null);
        if (now != null) {
            return new Scan(Result.OK, t.seat(), t.tier(), t.eventName(), now);
        }
        record After(Timestamp scannedAt, String status) {}
        After after = db.sql("select scanned_at, status from tickets where id = ?").param(p.ticketId())
                .query((rs, n) -> new After(rs.getTimestamp(1), rs.getString(2))).single();
        if (after.scannedAt() != null) {
            return new Scan(Result.ALREADY_SCANNED, t.seat(), t.tier(), t.eventName(), after.scannedAt().toInstant());
        }
        if (after.status().equals("CANCELLED")) {
            return new Scan(Result.CANCELLED, t.seat(), t.tier(), t.eventName(), null);
        }
        return Scan.of(Result.INVALID);
    }
}
