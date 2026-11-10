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
     * @param state OPEN (can still be paid), PAID, REFUNDED (paid, then the event was cancelled),
     *     CANCELLED (the event was cancelled before it was paid), RELEASED (let go) or EXPIRED
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
        EventService.Event event = events.get(eventId);
        if (!waitingRoom.mayBuy(event, fan.id())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "wait_in_queue");
        }
        if (coolingDown(eventId, fan.id())) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "cooldown");
        }
        String email = accounts.get(fan.id()).email();
        EventService.Quote quote = events.quote(event, items, promo, email);
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
        boolean cancelled = "CANCELLED".equals(events.get(r.eventId()).status());
        String state = orderId != null ? (cancelled ? "REFUNDED" : "PAID")
                : r.released() ? "RELEASED"
                : r.expired() ? "EXPIRED"
                : cancelled ? "CANCELLED"
                : "OPEN";
        return new HoldView(holdId, r.eventId(), r.expires(), read(r.quote()), state, orderId);
    }

    public void release(String holdId, CurrentUser fan) {
        HoldRow h = holdOf(holdId, fan);
        if (orderForHold(holdId).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "already_paid");
        }
        SeatResult r = seats.write(new SeatCommand.Release(h.id()));
        if (!r.ok()) {
            // The ledger no longer has the hold: a checkout confirmed it a moment ago, or it ran out.
            // Either way the promo use isn't ours to hand back here (the reconciler returns it for a
            // hold that ended unpaid).
            boolean paying = db.sql("""
                    select exists (select 1 from payment_attempts where hold_id = ? and state in ('CHARGING', 'CHARGED', 'DONE'))""")
                    .param(h.id()).query(Boolean.class).single();
            if (paying || orderForHold(holdId).isPresent()) {
                throw new ApiException(HttpStatus.CONFLICT, "already_paid");
            }
            return;
        }
        db.sql("update holds set released_at = now() where id = ?").param(h.id()).update();
        handBackPromo(h);
    }

    public Order checkout(String holdId, CurrentUser fan, String cardToken) {
        HoldRow h = holdOf(holdId, fan);
        Optional<Order> done = orderForHold(holdId);
        if (done.isPresent()) {
            if ("CANCELLED".equals(events.get(h.eventId()).status())) {
                throw new ApiException(HttpStatus.CONFLICT, "event_cancelled"); // and refunded
            }
            return done.get(); // a retry after the first answer got lost
        }

        // One charge per hold, however many times Pay is pressed and with whatever card: every click
        // joins the hold's current attempt, and a new attempt starts only after a decline.
        String key = attemptKey(holdId);
        boolean joining = db.sql("select count(*) from payment_attempts where idem_key = ?").param(key)
                .query(Long.class).single() > 0;
        // a hold that's been let go or has run out can't start a new charge (a retry of one already
        // under way still goes through, so it gets finished or refunded like any other)
        if (!joining && h.released()) {
            throw new ApiException(HttpStatus.CONFLICT, "no_such_hold");
        }
        if (!joining && h.expired()) {
            throw new ApiException(HttpStatus.CONFLICT, "hold_expired");
        }
        if (!joining && "CANCELLED".equals(events.get(h.eventId()).status())) {
            throw new ApiException(HttpStatus.CONFLICT, "event_cancelled");
        }
        // written down before any money moves; the reconciler finishes or refunds it if we die
        db.sql("""
                insert into payment_attempts (idem_key, hold_id, card_token, amount_cents, state)
                values (?, ?, ?, ?, 'CHARGING') on conflict do nothing""")
                .params(key, holdId, cardToken, h.totalCents()).update();
        Payments.Charge charge = charge(key, h.totalCents(), cardToken);
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
        if ("CANCELLED".equals(events.get(h.eventId()).status())) {
            sendRefunds();
            throw new ApiException(HttpStatus.CONFLICT, "event_cancelled");
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
            if (!paidByAnotherAttempt(h)) {
                handBackPromo(h); // the code's use stays taken if the hold was bought after all
            }
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
        User to = users.findByEmail(toEmail.strip().toLowerCase())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "no_such_user"));
        if (to.id().equals(fan.id())) {
            throw new ApiException(HttpStatus.CONFLICT, "thats_you");
        }
        // The ticket row stays locked until the move is saved, so a return, scan or cancel of the
        // same ticket waits and then sees the new owner.
        tx.executeWithoutResult(status -> {
            record Live(boolean scanned, boolean started) {}
            Live t = db.sql("""
                    select t.scanned_at is not null, e.starts_at <= now() from tickets t join events e on e.id = t.event_id
                    where t.id = ? and t.owner_id = ? and t.status = 'VALID' for update of t""")
                    .params(ticketId, fan.id())
                    .query((rs, n) -> new Live(rs.getBoolean(1), rs.getBoolean(2))).optional()
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "no_such_ticket"));
            if (t.scanned() || t.started()) {
                throw new ApiException(HttpStatus.CONFLICT, "too_late_to_transfer");
            }
            SeatResult r = seats.write(new SeatCommand.Transfer(ticketId, fan.id().toString(), to.id().toString()));
            switch (r.outcome()) {
                case OK -> db.sql("update tickets set owner_id = ?, version = version + 1 where id = ?")
                        .params(to.id(), ticketId).update();
                case LIMIT_REACHED -> throw new ApiException(HttpStatus.CONFLICT, "over_limit");
                case BAD_REQUEST -> throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_request");
                default -> throw new ApiException(HttpStatus.NOT_FOUND, "no_such_ticket");
            }
        });
    }

    /** Hands a ticket back for its face value; the seat goes back on sale. */
    public void giveBack(String ticketId, CurrentUser fan) {
        TicketRow t = ticketOf(ticketId, fan);
        record Returnable(boolean scanned, boolean started, boolean boughtByHolder) {}
        Returnable when = db.sql("""
                select t.scanned_at is not null, e.starts_at <= now(), o.fan_id = t.owner_id
                from tickets t join orders o on o.id = t.order_id join events e on e.id = t.event_id
                where t.id = ?""").param(ticketId)
                .query((rs, n) -> new Returnable(rs.getBoolean(1), rs.getBoolean(2), rs.getBoolean(3))).single();
        if (when.scanned() || when.started()) {
            throw new ApiException(HttpStatus.CONFLICT, "too_late_to_return");
        }
        if (!when.boughtByHolder()) {
            // the refund goes back to the card that paid, so only the buyer can hand a ticket back
            throw new ApiException(HttpStatus.CONFLICT, "only_the_buyer_can_return");
        }
        // Claim the ticket in Postgres first. Locking the order keeps an event cancel from working
        // out the order's refund at the same time, and the update only matches a ticket that is
        // still valid and unscanned, so a scan or a cancel that got there first wins.
        boolean claimed = tx.execute(status -> {
            db.sql("select id from orders where id = (select order_id from tickets where id = ?) for update")
                    .param(ticketId).query(UUID.class).single();
            int n = db.sql("""
                    update tickets set status = 'RETURNED'
                    where id = ? and owner_id = ? and status = 'VALID' and scanned_at is null""")
                    .params(ticketId, fan.id()).update();
            if (n == 1) {
                queueRefund("return:" + ticketId, t.paymentRef(), t.priceCents(), "ticket returned");
                // a note to put the seat back on sale, kept until the ledger has done it
                db.sql("insert into ledger_returns (ticket_id, fan_id) values (?, ?)").params(ticketId, fan.id()).update();
            }
            return n == 1;
        });
        if (!claimed) {
            throw new ApiException(HttpStatus.CONFLICT, "too_late_to_return");
        }
        sendRefunds();
        // Then put the seat back on sale. If the ledger can't be reached now, the reconciler tries
        // again; meanwhile the door refuses the ticket because Postgres says it was returned.
        finishLedgerReturn(ticketId, fan.id());
    }

    private void finishLedgerReturn(String ticketId, UUID fanId) {
        try {
            SeatResult r = seats.write(new SeatCommand.Return(ticketId, fanId.toString()));
            // NO_SUCH_TICKET: an earlier try got through and only its answer was lost
            if (r.ok() || r.outcome() == Outcome.NO_SUCH_TICKET) {
                db.sql("delete from ledger_returns where ticket_id = ?").param(ticketId).update();
            } else {
                log.warn("ticket {} was returned but the ledger said {}", ticketId, r.outcome());
            }
        } catch (RuntimeException e) {
            log.warn("ticket {} was returned but its seat isn't back on sale yet, will retry: {}", ticketId, e.toString());
        }
    }

    /**
     * Calls off an event: it comes off sale, every ticket stops scanning, and every order is refunded
     * in full (fees too), less anything already refunded for returned tickets. Ticket holders are
     * emailed. Safe to run again: each order's refund is queued once.
     */
    public void cancelEvent(UUID eventId, CurrentUser by) {
        EventService.Event e = events.requireOwner(eventId, by);
        if (e.status().equals("CANCELLED")) {
            throw new ApiException(HttpStatus.CONFLICT, "already_cancelled");
        }
        if (!e.startsAt().isAfter(Instant.now())) {
            throw new ApiException(HttpStatus.CONFLICT, "event_started");
        }
        List<String> holders = tx.execute(status -> {
            db.sql("update events set status = 'CANCELLED' where id = ?").param(eventId).update();
            return refundCancelled(eventId);
        });
        for (String email : holders) {
            mailer.send(email, "Cancelled: " + e.name(), """
                    Sorry: %s has been cancelled.

                    Your tickets have been refunded in full, fees included, to the card you paid with.
                    Refunds usually show up within 5 to 10 business days.
                    """.formatted(e.name()));
        }
        sendRefunds();
    }

    /** Cancels the event's live tickets and queues each order's refund. Returns who held them. */
    private List<String> refundCancelled(UUID eventId) {
        // lock the orders first, the same way a return does, so the two can't both refund a ticket
        db.sql("select id from orders where event_id = ? order by id for update").param(eventId).query(UUID.class).list();
        // whoever holds a live ticket, and whoever paid for it (the refund goes to their card)
        List<String> holders = db.sql("""
                select u.email from tickets t join users u on u.id = t.owner_id
                where t.event_id = ? and t.status = 'VALID'
                union
                select u.email from tickets t join orders o on o.id = t.order_id join users u on u.id = o.fan_id
                where t.event_id = ? and t.status = 'VALID'""").params(eventId, eventId).query(String.class).list();
        db.sql("update tickets set status = 'CANCELLED' where event_id = ? and status = 'VALID'").param(eventId).update();
        record Owed(UUID orderId, String ref, long cents) {}
        db.sql("""
                select o.id, o.payment_ref,
                       o.total_cents - coalesce((select sum(r.amount_cents) from refunds r
                                                 where r.payment_ref = o.payment_ref), 0)
                from orders o where o.event_id = ?""").param(eventId)
                .query((rs, n) -> new Owed(rs.getObject(1, UUID.class), rs.getString(2), rs.getLong(3)))
                .list()
                .stream().filter(o -> o.cents() > 0)
                .forEach(o -> queueRefund("cancel:" + o.orderId(), o.ref(), o.cents(), "event cancelled"));
        return holders;
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
                .query((rs, n) -> new HoldRow(rs.getString(1), rs.getObject(2, UUID.class), null, 0, rs.getString(3),
                        false, true))
                .list()
                .forEach(this::handBackPromo);

        // returned tickets whose seat the ledger couldn't take back at the time
        record PendingReturn(String ticketId, UUID fanId) {}
        db.sql("select ticket_id, fan_id from ledger_returns where created_at < ?").param(cutoff)
                .query((rs, n) -> new PendingReturn(rs.getString(1), rs.getObject(2, UUID.class))).list()
                .forEach(p -> finishLedgerReturn(p.ticketId(), p.fanId()));

        // a payment that was mid-flight when its event was cancelled can still land as an order;
        // refund those too
        db.sql("""
                select distinct t.event_id from tickets t join events e on e.id = t.event_id
                where e.status = 'CANCELLED' and t.status = 'VALID'""").query(UUID.class).list()
                .forEach(eventId -> tx.executeWithoutResult(status -> refundCancelled(eventId)));

        sendRefunds();
    }

    // ---------------------------------------------------------------- internals

    private record HoldRow(String id, UUID eventId, EventService.Quote quote, long totalCents, String promoCode,
                           boolean released, boolean expired) {}

    /** A free order (say, a 100% promo with no fees) never reaches the card processor. */
    private Payments.Charge charge(String key, long cents, String token) {
        return cents == 0 ? new Payments.Charge(true, "free_" + key, null)
                : payments.charge(key, cents, token, "Seatline tickets");
    }

    private record TicketRow(String id, long priceCents, String paymentRef) {}

    private HoldRow holdOf(String holdId, CurrentUser fan) {
        return db.sql("""
                        select id, event_id, quote, total_cents, promo_code, released_at is not null, expires_at <= now()
                        from holds where id = ? and fan_id = ?""")
                .params(holdId, fan.id())
                .query((rs, n) -> new HoldRow(rs.getString(1), rs.getObject(2, UUID.class), read(rs.getString(3)),
                        rs.getLong(4), rs.getString(5), rs.getBoolean(6), rs.getBoolean(7)))
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

    /**
     * True if one of this fan's holds for the event ended unpaid in the last two minutes: it ran
     * out, or it was let go after sitting on the seats for more than half its time (otherwise
     * letting go at 9:59 would dodge the wait). Letting seats go early never costs anything.
     */
    private boolean coolingDown(UUID eventId, UUID fan) {
        return db.sql("""
                select exists (select 1 from holds h
                    where h.fan_id = ? and h.event_id = ?
                      and not exists (select 1 from orders o where o.hold_id = h.id)
                      and ((h.released_at is null and h.expires_at <= now()
                            and h.expires_at > now() - make_interval(secs => ?))
                        or (h.released_at > h.expires_at - make_interval(secs => ?)
                            and h.released_at > now() - make_interval(secs => ?))))""")
                .params(fan, eventId, COOLDOWN.toSeconds(), HOLD_TTL.toSeconds() / 2, COOLDOWN.toSeconds())
                .query(Boolean.class).single();
    }

    private HoldRow holdRow(String holdId) {
        return db.sql("""
                        select id, event_id, quote, total_cents, promo_code, released_at is not null, expires_at <= now()
                        from holds where id = ?""").param(holdId)
                .query((rs, n) -> new HoldRow(rs.getString(1), rs.getObject(2, UUID.class), read(rs.getString(3)),
                        rs.getLong(4), rs.getString(5), rs.getBoolean(6), rs.getBoolean(7)))
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
            Payments.Charge c = charge(key, h.totalCents(), token);
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
                if (r.cents() > 0) { // nothing to send back for a free ticket
                    payments.refund(r.id(), r.ref(), r.cents());
                }
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

    /** The hold's attempt that isn't a decline, or the next number after the declines. */
    private String attemptKey(String holdId) {
        record Attempt(String key, String state) {}
        List<Attempt> attempts = db.sql("select idem_key, state from payment_attempts where hold_id = ? order by created_at")
                .param(holdId).query((rs, n) -> new Attempt(rs.getString(1), rs.getString(2))).list();
        return attempts.stream().filter(a -> !a.state().equals("DECLINED")).map(Attempt::key).findFirst()
                .orElse(holdId + ":" + (attempts.size() + 1));
    }

    /** True if the ledger has the hold's tickets, whoever's payment they were confirmed with. */
    private boolean paidByAnotherAttempt(HoldRow h) {
        return seats.ticket(ticketId(h.id(), h.quote().lines().getFirst().seat())).isPresent();
    }

    /** True if an earlier checkout of this hold got as far as the ledger with this payment. */
    private boolean alreadyConfirmed(HoldRow h, String paymentRef) {
        String seat = h.quote().lines().getFirst().seat();
        return seats.ticket(ticketId(h.id(), seat)).map(t -> paymentRef.equals(t.paymentRef())).orElse(false);
    }

    private Order saveOrder(HoldRow h, String paymentRef) {
        UUID orderId = UUID.randomUUID();
        // a cancel changes this row, so this waits for one that's under way and then sees it
        boolean cancelled = db.sql("select status = 'CANCELLED' from events where id = ? for share")
                .param(h.eventId()).query(Boolean.class).single();
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
        if (cancelled) {
            // paid while the event was being called off: refund it now and say so, not "here are your tickets"
            db.sql("update tickets set status = 'CANCELLED' where order_id = ?").param(orderId).update();
            if (h.totalCents() > 0) {
                queueRefund("cancel:" + orderId, paymentRef, h.totalCents(), "event cancelled");
            }
            String name = order.tickets().getFirst().eventName();
            mailer.send(accounts.get(fan).email(), "Cancelled: " + name, """
                    Sorry: %s was cancelled while your payment went through.

                    Your payment has been refunded in full to the card you paid with.
                    Refunds usually show up within 5 to 10 business days.
                    """.formatted(name));
            return order;
        }
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
