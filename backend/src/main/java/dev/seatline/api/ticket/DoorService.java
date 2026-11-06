package dev.seatline.api.ticket;

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
import java.util.Optional;
import java.util.UUID;

/** QR codes for fans, and checking them at the door. */
@Service
public class DoorService {

    /** OLD_CODE: from before a transfer. EXPIRED: right ticket, but a code more than ~15 s old (a screenshot). */
    public enum Result { OK, ALREADY_SCANNED, WRONG_EVENT, OLD_CODE, EXPIRED, INVALID }

    public record Scan(Result result, String seat, String tier, String eventName, Instant scannedAt) {
        static Scan of(Result r) {
            return new Scan(r, null, null, null, null);
        }
    }

    private final JdbcClient db;
    private final Seats seats;
    private final TicketCodes codes;
    private final Clock clock;

    public DoorService(JdbcClient db, Seats seats, TicketCodes codes, Clock clock) {
        this.db = db;
        this.seats = seats;
        this.codes = codes;
        this.clock = clock;
    }

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
    public Scan scan(String code, UUID atEvent) {
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
        record Row(UUID eventId, String seat, String tier, String eventName, Instant scannedAt) {}
        Row t = db.sql("""
                select t.event_id, t.seat, t.tier, e.name, t.scanned_at from tickets t join events e on e.id = t.event_id
                where t.id = ?""").param(p.ticketId())
                .query((rs, n) -> new Row(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getTimestamp(5) == null ? null : rs.getTimestamp(5).toInstant()))
                .optional().orElse(null);
        if (t == null) {
            return Scan.of(Result.INVALID);
        }
        if (atEvent != null && !atEvent.equals(t.eventId())) {
            return new Scan(Result.WRONG_EVENT, t.seat(), t.tier(), t.eventName(), null);
        }
        // the update only matches once, so two doors scanning the same code can't both let it in
        Instant now = db.sql("""
                update tickets set scanned_at = now() where id = ? and scanned_at is null returning scanned_at""")
                .param(p.ticketId()).query(Timestamp.class).optional().map(Timestamp::toInstant).orElse(null);
        if (now == null) {
            Instant first = db.sql("select scanned_at from tickets where id = ?").param(p.ticketId())
                    .query(Timestamp.class).single().toInstant();
            return new Scan(Result.ALREADY_SCANNED, t.seat(), t.tier(), t.eventName(), first);
        }
        return new Scan(Result.OK, t.seat(), t.tier(), t.eventName(), now);
    }
}
