package dev.seatline.api.seats;

import dev.seatline.api.security.SeatlineSettings;
import dev.seatline.ledger.client.LedgerClient;
import dev.seatline.ledger.seats.SeatCommand;
import dev.seatline.ledger.seats.SeatLedger.SeatStatus;
import dev.seatline.ledger.seats.SeatLedger.SeatView;
import dev.seatline.ledger.seats.SeatResult;
import dev.seatline.ledger.server.LedgerMain;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * The API's way into the seat ledger. Who owns a seat is only ever decided there; this class just
 * sends commands and reads seat maps.
 *
 * <p>Writes go through a pool of ledger sessions. Each session has its own client id, and the
 * ledger keeps the last answer for every client id it has seen (that's how a retried write isn't
 * applied twice), so a fixed pool keeps that table small instead of growing by one per request.
 */
@Component
public class Seats {

    private static final int SESSIONS = 64;

    private final LedgerClient ledger;
    private final BlockingQueue<LedgerClient.Session> sessions = new ArrayBlockingQueue<>(SESSIONS);

    public Seats(SeatlineSettings settings) {
        if (settings.ledger() == null || settings.ledger().isBlank()) {
            throw new IllegalStateException("seatline.ledger (SEATLINE_LEDGER) must list the ledger nodes, e.g. 1=localhost:8001");
        }
        this.ledger = new LedgerClient(LedgerMain.parseCluster(settings.ledger()), settings.ledgerTimeout());
        for (int i = 0; i < SESSIONS; i++) {
            sessions.add(ledger.session());
        }
    }

    /** Commits one command. Blocks while every session is busy. */
    public SeatResult write(SeatCommand command) {
        LedgerClient.Session session;
        try {
            session = sessions.take();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted waiting for a ledger session", e);
        }
        try {
            return session.write(command);
        } finally {
            sessions.add(session);
        }
    }

    /** Seat label to status for an event, read through the leader so it's never stale. Null if unknown. */
    public Map<String, SeatStatus> statuses(String eventId) {
        List<SeatView> seats = ledger.seatMap(eventId);
        if (seats == null) {
            return null;
        }
        Map<String, SeatStatus> out = new LinkedHashMap<>();
        seats.forEach(s -> out.put(s.seatId(), s.status()));
        return out;
    }

    /** The ledger's record of a ticket, if it has one. */
    public java.util.Optional<dev.seatline.ledger.seats.SeatLedger.Ticket> ticket(String ticketId) {
        return ledger.ticket(ticketId);
    }

    @PreDestroy
    void close() {
        ledger.close();
    }
}
