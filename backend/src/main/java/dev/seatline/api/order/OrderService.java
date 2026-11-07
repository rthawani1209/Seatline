package dev.seatline.api.order;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.seatline.api.account.AccountService;
import dev.seatline.api.account.Mailer;
import dev.seatline.api.account.User;
import dev.seatline.api.account.UserRepository;
import dev.seatline.api.event.EventService;
import dev.seatline.api.payment.Payments;
import dev.seatline.api.queue.WaitingRoom;
import dev.seatline.api.seats.Seats;
import dev.seatline.api.web.ApiException;
import dev.seatline.api.web.CurrentUser;
import dev.seatline.ledger.seats.SeatCommand;
import dev.seatline.ledger.seats.SeatResult;
import dev.seatline.ledger.seats.SeatResult.Outcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
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
    /** A fan whose hold ran out unpaid waits this long before holding again, so nobody can sit on seats. */
    static final Duration COOLDOWN = Duration.ofMinutes(2);
    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    public record Hold(String holdId, Instant expiresAt, EventService.Quote quote) {}

    public record TicketView(String id, UUID eventId, String eventName, String seat, String tier, long priceCents) {}

    public record Order(UUID id, UUID eventId, long totalCents, List<TicketView> tickets) {}

    /**
     * A fan's hold, for the checkout page after a reload.
     *
     * @param state OPEN (can still be paid), PAID, RELEASED (let go) or EXPIRED
     */
    public record HoldView(String holdId, UUID eventId, Instant expiresAt, EventService.Quote quote, String state,
                           UUID orderId) {}

    private final JdbcClient db;
    private final TransactionTemplate tx;
    private final EventService events;
    private final AccountService accounts;
    private final UserRepository users;
    private final Seats seats;
    private final Payments payments;
    private final Mailer mailer;
    private final ObjectMapper json;
    private final WaitingRoom waitingRoom;

    public OrderService(JdbcClient db, TransactionTemplate tx, EventService events, AccountService accounts,
                        UserRepository users, Seats seats, Payments payments, Mailer mailer, ObjectMapper json,
                        WaitingRoom waitingRoom) {
        this.db = db;
        this.tx = tx;
        this.events = events;
        this.accounts = accounts;
        this.users = users;
        this.seats = seats;
        this.payments = payments;
        this.mailer = mailer;
        this.json = json;
        this.waitingRoom = waitingRoom;
    }

    public Hold hold(UUID eventId, CurrentUser fan, List<EventService.QuoteItem> items, String promo) {
        if (!waitingRoom.mayBuy(events.get(eventId), fan.id())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "wait_in_queue");
        }
        if (coolingDown(eventId, fan.id())) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "cooldown");
        }
        String email = accounts.get(fan.id()).email();
        EventService.Quote quote = events.quote(eventId, items, promo, email);
        List<String> wanted = quote.lines().stream().map(EventService.QuoteLine::seat).toList();
        String holdId = "h" + UUID.randomUUID().toString().replace("-", "");

        // take one use of the promo code now, so the last use can't go to several fans at once
        if (quote.promoCode() != null && db.sql("""
                update promo_codes set used = used + 1
                where event_id = ? and code = ? and used < max_uses and (expires_at is null or expires_at > now())""")
                .params(eventId, quote.promoCode()).update() == 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_promo_code");
        }
        SeatResult r;
        try {
            r = seats.write(new SeatCommand.Hold(eventId.toString(), wanted, fan.id().toString(), holdId,
                    HOLD_TTL.toMillis()));
        } catch (RuntimeException e) {
            returnPromoUse(eventId, quote.promoCode());
            throw e;
        }
        if (!r.ok()) {
            returnPromoUse(eventId, quote.promoCode());
        }
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

    public HoldView holdView(String holdId, CurrentUser fan) {
        record Row(UUID eventId, String quote, Instant expires, boolean released, boolean expired) {}
        Row r = db.sql("""
                select event_id, quote, expires_at, released_at is not null, expires_at <= now()
                from holds where id = ? and fan_id = ?""")
                .params(holdId, fan.id())
                .query((rs, n) -> new Row(rs.getObject(1, UUID.class), rs.getString(2),
                        rs.getTimestamp(3).toInstant(), rs.getBoolean(4), rs.getBoolean(5)))
                .optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "no_such_hold"));
        UUID orderId = db.sql("select id from orders where hold_id = ?").param(holdId).query(UUID.class)
                .optional().orElse(null);
        String state = orderId != null ? "PAID"
                : r.released() ? "RELEASED"
                : r.expired() ? "EXPIRED"
                : "OPEN";
        return new HoldView(holdId, r.eventId(), r.expires(), read(r.quote()), state, orderId);
    }

    public void release(String holdId, CurrentUser fan) {
        HoldRow h = holdOf(holdId, fan);
        if (orderForHold(holdId).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "already_paid");
        }
        seats.write(new SeatCommand.Release(h.id()));
        db.sql("update holds set released_at = now() where id = ?").param(h.id()).update();
        handBackPromo(h);
    }

    public Order checkout(String holdId, CurrentUser fan, String cardToken) {
        HoldRow h = holdOf(holdId, fan);
        Optional<Order> done = orderForHold(holdId);
        if (done.isPresent()) {
            return done.get(); // a retry after the first answer got lost
        }

        // written down before any money moves; the reconciler finishes or refunds it if we die
        String key = holdId + ":" + cardToken;
        db.sql("""
                insert into payment_attempts (idem_key, hold_id, card_token, amount_cents, state)
                values (?, ?, ?, ?, 'CHARGING') on conflict do nothing""")
                .params(key, holdId, cardToken, h.totalCents()).update();
        Payments.Charge charge = payments.charge(key, h.totalCents(), cardToken, "Seatline tickets");
        if (!charge.approved()) {
            attemptState(key, "DECLINED", null);
            throw new ApiException(HttpStatus.PAYMENT_REQUIRED, "payment_declined");
        }
        attemptState(key, "CHARGED", charge.ref());
        step("charged");

        Order order = finish(h, key, charge.ref());
        if (order == null) {
            throw new ApiException(HttpStatus.CONFLICT, "hold_expired");
        }
        return order;
    }

    /**
     * Turns a paid hold into an order, or refunds it if the ledger won't issue the tickets. Used by
     * checkout and by the reconciler; safe to run more than once for the same payment.
     */
    private Order finish(HoldRow h, String key, String paymentRef) {
        SeatResult r = seats.write(new SeatCommand.Confirm(h.id(), paymentRef));
        boolean confirmed = r.ok() || (r.outcome() == Outcome.NO_SUCH_HOLD && alreadyConfirmed(h, paymentRef));
        if (!confirmed) {
            queueRefund("attempt:" + key, paymentRef, h.totalCents(), "hold ended before payment");
            attemptState(key, "REFUNDED", paymentRef);
            handBackPromo(h);
            sendRefunds();
            return null;
        }
        step("confirmed");
        try {
            return tx.execute(status -> {
                Order order = saveOrder(h, paymentRef);
                attemptState(key, "DONE", paymentRef);
                return order;
            });
        } catch (DuplicateKeyException e) {
            // another checkout of the same hold (same card, so same charge) saved it first
            attemptState(key, "DONE", paymentRef);
            return orderForHold(h.id()).orElseThrow();
        }
    }

    /** Test seam: a place where the process could die. Does nothing in normal running. */
    void step(String name) {
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
        tx.executeWithoutResult(status -> {
            db.sql("update tickets set status = 'RETURNED' where id = ?").param(ticketId).update();
            queueRefund("return:" + ticketId, t.paymentRef(), t.priceCents(), "ticket returned");
        });
        sendRefunds();
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

    /**
     * Cleans up after anything that died halfway: payments taken without an order (finish or
     * refund them), refunds that failed to send, and promo uses held by holds that ran out.
     */
    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    void reconcile() {
        try {
            reconcileNow(Duration.ofMinutes(1));
        } catch (RuntimeException e) {
            log.warn("reconcile round failed, will retry: {}", e.toString());
        }
    }

    void reconcileNow(Duration olderThan) {
        Timestamp cutoff = Timestamp.from(Instant.now().minus(olderThan));
        record Attempt(String key, String holdId, String token, String state, String ref) {}
        List<Attempt> stuck = db.sql("""
                select idem_key, hold_id, card_token, state, payment_ref from payment_attempts
                where state in ('CHARGING', 'CHARGED') and created_at < ?""").param(cutoff)
                .query((rs, n) -> new Attempt(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)))
                .list();
        for (Attempt a : stuck) {
            // one bad attempt must not stop the rest (or the refunds below) from being handled
            try {
                reconcile(a.key(), a.holdId(), a.token(), a.state(), a.ref());
            } catch (RuntimeException e) {
                log.warn("couldn't reconcile payment attempt {}, will retry: {}", a.key(), e.toString());
            }
        }

        // holds that ended unpaid give their promo use back
        db.sql("""
                select id, event_id, promo_code from holds h
                where promo_code is not null and not promo_returned and expires_at < ?
                  and not exists (select 1 from orders o where o.hold_id = h.id)""").param(cutoff)
                .query((rs, n) -> new HoldRow(rs.getString(1), rs.getObject(2, UUID.class), null, 0, rs.getString(3)))
                .list()
                .forEach(this::handBackPromo);

        sendRefunds();
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

    /** True if one of this fan's holds for the event ran out unpaid in the last two minutes. */
    private boolean coolingDown(UUID eventId, UUID fan) {
        return db.sql("""
                select exists (select 1 from holds h
                    where h.fan_id = ? and h.event_id = ? and h.released_at is null
                      and h.expires_at <= now() and h.expires_at > now() - make_interval(secs => ?)
                      and not exists (select 1 from orders o where o.hold_id = h.id))""")
                .params(fan, eventId, COOLDOWN.toSeconds()).query(Boolean.class).single();
    }

    private HoldRow holdRow(String holdId) {
        return db.sql("select id, event_id, quote, total_cents, promo_code from holds where id = ?").param(holdId)
                .query((rs, n) -> new HoldRow(rs.getString(1), rs.getObject(2, UUID.class), read(rs.getString(3)),
                        rs.getLong(4), rs.getString(5)))
                .single();
    }

    private void attemptState(String key, String state, String ref) {
        db.sql("update payment_attempts set state = ?, payment_ref = coalesce(?, payment_ref) where idem_key = ?")
                .params(state, ref, key).update();
    }

    private void queueRefund(String id, String paymentRef, long cents, String reason) {
        db.sql("insert into refunds (id, payment_ref, amount_cents, reason) values (?, ?, ?, ?) on conflict do nothing")
                .params(id, paymentRef, cents, reason).update();
    }

    private void reconcile(String key, String holdId, String token, String state, String ref) {
        HoldRow h = holdRow(holdId);
        if (state.equals("CHARGING")) {
            // we don't know if the charge went through; asking again with the same key tells us
            Payments.Charge c = payments.charge(key, h.totalCents(), token, "Seatline tickets");
            if (!c.approved()) {
                attemptState(key, "DECLINED", null);
                return;
            }
            ref = c.ref();
            attemptState(key, "CHARGED", ref);
        }
        if (orderForHold(h.id()).isPresent()) {
            // the order was saved by another attempt with a different card: this charge is extra
            queueRefund("attempt:" + key, ref, h.totalCents(), "paid twice for one hold");
            attemptState(key, "REFUNDED", ref);
            return;
        }
        finish(h, key, ref);
    }

    /** Sends every pending refund. One that fails stays pending for the next round. */
    void sendRefunds() {
        record Refund(String id, String ref, long cents) {}
        List<Refund> pending = db.sql("select id, payment_ref, amount_cents from refunds where state = 'PENDING'")
                .query((rs, n) -> new Refund(rs.getString(1), rs.getString(2), rs.getLong(3))).list();
        for (Refund r : pending) {
            try {
                payments.refund(r.id(), r.ref(), r.cents());
                db.sql("update refunds set state = 'DONE' where id = ?").param(r.id()).update();
            } catch (RuntimeException e) {
                log.warn("refund {} failed, will retry: {}", r.id(), e.toString());
            }
        }
    }

    /** Gives a hold's promo code use back, once. */
    private void handBackPromo(HoldRow h) {
        if (h.promoCode() != null
                && db.sql("update holds set promo_returned = true where id = ? and not promo_returned").param(h.id()).update() == 1) {
            returnPromoUse(h.eventId(), h.promoCode());
        }
    }

    private void returnPromoUse(UUID eventId, String code) {
        if (code != null) {
            db.sql("update promo_codes set used = used - 1 where event_id = ? and code = ? and used > 0")
                    .params(eventId, code).update();
        }
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
