package dev.seatline.api.order;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.seatline.api.account.AccountService;
import dev.seatline.api.account.Mailer;
import dev.seatline.api.account.User;
import dev.seatline.api.account.UserRepository;
import dev.seatline.api.event.EventService;
import dev.seatline.api.payment.Payments;
import dev.seatline.api.seats.Seats;
import dev.seatline.api.web.ApiException;
import dev.seatline.api.web.CurrentUser;
import dev.seatline.ledger.seats.SeatCommand;
import dev.seatline.ledger.seats.SeatResult;
import dev.seatline.ledger.seats.SeatResult.Outcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Holding seats, paying for them, and what fans can do with tickets afterwards.
 *
 * <p>The order of steps is what keeps money and seats consistent:
 * <ol>
 *   <li>hold: the ledger sets the seats aside for 10 minutes (all or none), then we save the quote.
 *   <li>checkout: charge the card first (idempotent per hold and card), then ask the ledger to turn
 *       the hold into tickets. If the hold ran out in between, the ledger says no and we refund.
 *   <li>only then write the order and tickets to Postgres.
 * </ol>
 * If the API dies after the ledger confirmed but before Postgres was written, retrying checkout
 * finds the tickets in the ledger (their ids come from the hold id) and finishes the job.
 */
@Service
public class OrderService {

    static final Duration HOLD_TTL = Duration.ofMinutes(10);
    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    public record Hold(String holdId, Instant expiresAt, EventService.Quote quote) {}

    public record TicketView(String id, UUID eventId, String eventName, String seat, String tier, long priceCents) {}

    public record Order(UUID id, UUID eventId, long totalCents, List<TicketView> tickets) {}

    private final JdbcClient db;
    private final TransactionTemplate tx;
    private final EventService events;
    private final AccountService accounts;
    private final UserRepository users;
    private final Seats seats;
    private final Payments payments;
    private final Mailer mailer;
    private final ObjectMapper json;

    public OrderService(JdbcClient db, TransactionTemplate tx, EventService events, AccountService accounts,
                        UserRepository users, Seats seats, Payments payments, Mailer mailer, ObjectMapper json) {
        this.db = db;
        this.tx = tx;
        this.events = events;
        this.accounts = accounts;
        this.users = users;
        this.seats = seats;
        this.payments = payments;
        this.mailer = mailer;
        this.json = json;
    }

    public Hold hold(UUID eventId, CurrentUser fan, List<EventService.QuoteItem> items, String promo) {
        String email = accounts.get(fan.id()).email();
        EventService.Quote quote = events.quote(eventId, items, promo, email);
        List<String> wanted = quote.lines().stream().map(EventService.QuoteLine::seat).toList();
        String holdId = "h" + UUID.randomUUID().toString().replace("-", "");

        SeatResult r = seats.write(new SeatCommand.Hold(eventId.toString(), wanted, fan.id().toString(), holdId,
                HOLD_TTL.toMillis()));
        switch (r.outcome()) {
            case OK -> { }
            case SEATS_TAKEN -> throw new ApiException(HttpStatus.CONFLICT, "seats_taken");
            case LIMIT_REACHED -> throw new ApiException(HttpStatus.CONFLICT, "over_limit");
            case NO_SUCH_SEAT -> throw new ApiException(HttpStatus.BAD_REQUEST, "no_such_seat");
            default -> throw new IllegalStateException("ledger refused hold: " + r.outcome());
        }
        Instant expires = Instant.ofEpochMilli(r.expiresAt());
        db.sql("""
                insert into holds (id, event_id, fan_id, quote, total_cents, promo_code, expires_at)
                values (?, ?, ?, ?::jsonb, ?, ?, ?)""")
                .params(holdId, eventId, fan.id(), write(quote), quote.totalCents(), quote.promoCode(),
                        Timestamp.from(expires))
                .update();
        return new Hold(holdId, expires, quote);
    }

    public void release(String holdId, CurrentUser fan) {
        HoldRow h = holdOf(holdId, fan);
        seats.write(new SeatCommand.Release(h.id()));
    }

    public Order checkout(String holdId, CurrentUser fan, String cardToken) {
        HoldRow h = holdOf(holdId, fan);
        Optional<Order> done = orderForHold(holdId);
        if (done.isPresent()) {
            return done.get(); // a retry after the first answer got lost
        }

        Payments.Charge charge = payments.charge(holdId + ":" + cardToken, h.totalCents(), cardToken, "Seatline tickets");
        if (!charge.approved()) {
            throw new ApiException(HttpStatus.PAYMENT_REQUIRED, "payment_declined");
        }

        SeatResult r = seats.write(new SeatCommand.Confirm(holdId, charge.ref()));
        boolean confirmed = r.ok() || (r.outcome() == Outcome.NO_SUCH_HOLD && alreadyConfirmed(h, charge.ref()));
        if (!confirmed) {
            payments.refund(charge.ref(), h.totalCents());
            throw new ApiException(HttpStatus.CONFLICT, "hold_expired");
        }
        return tx.execute(status -> saveOrder(h, charge.ref()));
    }

    public List<TicketView> tickets(CurrentUser fan) {
        return db.sql("""
                select t.id, t.event_id, e.name, t.seat, t.tier, t.price_cents
                from tickets t join events e on e.id = t.event_id
                where t.owner_id = ? and t.status = 'VALID' order by e.starts_at, t.seat""")
                .param(fan.id())
                .query((rs, n) -> new TicketView(rs.getString(1), rs.getObject(2, UUID.class), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getLong(6)))
                .list();
    }

    /** Gives a ticket to someone else. Their old QR code (version) stops working. */
    public void transfer(String ticketId, CurrentUser fan, String toEmail) {
        ticketOf(ticketId, fan);
        User to = users.findByEmail(toEmail.strip().toLowerCase())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "no_such_user"));
        SeatResult r = seats.write(new SeatCommand.Transfer(ticketId, fan.id().toString(), to.id().toString()));
        switch (r.outcome()) {
            case OK -> db.sql("update tickets set owner_id = ?, version = version + 1 where id = ?")
                    .params(to.id(), ticketId).update();
            case LIMIT_REACHED -> throw new ApiException(HttpStatus.CONFLICT, "over_limit");
            case BAD_REQUEST -> throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_request");
            default -> throw new ApiException(HttpStatus.NOT_FOUND, "no_such_ticket");
        }
    }

    /** Hands a ticket back for its face value; the seat goes back on sale. */
    public void giveBack(String ticketId, CurrentUser fan) {
        TicketRow t = ticketOf(ticketId, fan);
        SeatResult r = seats.write(new SeatCommand.Return(ticketId, fan.id().toString()));
        if (!r.ok()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "no_such_ticket");
        }
        db.sql("update tickets set status = 'RETURNED' where id = ?").param(ticketId).update();
        payments.refund(t.paymentRef(), t.priceCents());
    }

    /** Frees holds whose time is up. Every API server sends it; doing it twice is harmless. */
    @Scheduled(fixedDelay = 5_000, initialDelay = 5_000)
    void expireHolds() {
        try {
            seats.write(new SeatCommand.ExpireDue());
        } catch (RuntimeException e) {
            log.debug("couldn't expire holds this round: {}", e.toString());
        }
    }

    // ---------------------------------------------------------------- internals

    private record HoldRow(String id, UUID eventId, EventService.Quote quote, long totalCents, String promoCode) {}

    private record TicketRow(String id, long priceCents, String paymentRef) {}

    private HoldRow holdOf(String holdId, CurrentUser fan) {
        return db.sql("select id, event_id, quote, total_cents, promo_code from holds where id = ? and fan_id = ?")
                .params(holdId, fan.id())
                .query((rs, n) -> new HoldRow(rs.getString(1), rs.getObject(2, UUID.class), read(rs.getString(3)),
                        rs.getLong(4), rs.getString(5)))
                .optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "no_such_hold"));
    }

    private TicketRow ticketOf(String ticketId, CurrentUser fan) {
        return db.sql("""
                select t.id, t.price_cents, o.payment_ref from tickets t join orders o on o.id = t.order_id
                where t.id = ? and t.owner_id = ? and t.status = 'VALID'""")
                .params(ticketId, fan.id())
                .query((rs, n) -> new TicketRow(rs.getString(1), rs.getLong(2), rs.getString(3)))
                .optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "no_such_ticket"));
    }

    /** True if an earlier checkout of this hold got as far as the ledger with this payment. */
    private boolean alreadyConfirmed(HoldRow h, String paymentRef) {
        String seat = h.quote().lines().getFirst().seat();
        return seats.ticket(ticketId(h.id(), seat)).map(t -> paymentRef.equals(t.paymentRef())).orElse(false);
    }

    private Order saveOrder(HoldRow h, String paymentRef) {
        UUID orderId = UUID.randomUUID();
        UUID fan = db.sql("select fan_id from holds where id = ?").param(h.id()).query(UUID.class).single();
        db.sql("insert into orders (id, hold_id, event_id, fan_id, total_cents, payment_ref) values (?, ?, ?, ?, ?, ?)")
                .params(orderId, h.id(), h.eventId(), fan, h.totalCents(), paymentRef).update();
        for (EventService.QuoteLine line : h.quote().lines()) {
            db.sql("""
                    insert into tickets (id, order_id, event_id, seat, tier, price_cents, owner_id)
                    values (?, ?, ?, ?, ?, ?, ?)""")
                    .params(ticketId(h.id(), line.seat()), orderId, h.eventId(), line.seat(), line.tier(),
                            line.price().total(), fan)
                    .update();
        }
        if (h.promoCode() != null) {
            db.sql("update promo_codes set used = used + 1 where event_id = ? and code = ?")
                    .params(h.eventId(), h.promoCode()).update();
        }
        Order order = orderForHold(h.id()).orElseThrow();
        StringBuilder body = new StringBuilder("Your tickets for " + order.tickets().getFirst().eventName() + ":\n");
        order.tickets().forEach(t -> body.append("  ").append(t.seat()).append(" (").append(t.tier()).append(")\n"));
        body.append(String.format("Total charged: $%d.%02d%n", order.totalCents() / 100, order.totalCents() % 100));
        mailer.send(accounts.get(fan).email(), "Your Seatline tickets", body.toString());
        return order;
    }

    private Optional<Order> orderForHold(String holdId) {
        record Row(UUID id, UUID eventId, long total) {}
        // tickets are fetched after the order row is read, never inside its row mapper (see EventService.get)
        return db.sql("select id, event_id, total_cents from orders where hold_id = ?").param(holdId)
                .query((rs, n) -> new Row(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getLong(3)))
                .optional()
                .map(r -> new Order(r.id(), r.eventId(), r.total(), ticketsOfOrder(r.id())));
    }

    private List<TicketView> ticketsOfOrder(UUID orderId) {
        return new ArrayList<>(db.sql("""
                select t.id, t.event_id, e.name, t.seat, t.tier, t.price_cents
                from tickets t join events e on e.id = t.event_id where t.order_id = ? order by t.seat""")
                .param(orderId)
                .query((rs, n) -> new TicketView(rs.getString(1), rs.getObject(2, UUID.class), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getLong(6)))
                .list());
    }

    /** The ledger names tickets after the hold and seat (see SeatLedger.confirm). */
    static String ticketId(String holdId, String seat) {
        return "T-" + holdId + "-" + seat;
    }

    private String write(EventService.Quote quote) {
        try {
            return json.writeValueAsString(quote);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private EventService.Quote read(String text) {
        try {
            return json.readValue(text, EventService.Quote.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
