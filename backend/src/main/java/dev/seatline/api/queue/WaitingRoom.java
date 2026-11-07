package dev.seatline.api.queue;

import dev.seatline.api.event.EventService;
import dev.seatline.api.web.ApiException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The waiting room in front of a busy on-sale. Fans join a line and are let in at a steady rate,
 * so the seat map and the ledger see a few hundred fans a minute instead of everyone at once.
 *
 * <p>All state lives in Redis, so every API server agrees on the line:
 * <ul>
 *   <li>{@code queue:<event>}: each fan's number in line (a sorted set; a number never changes).
 *   <li>{@code queue:<event>:seq}: the last number given out.
 *   <li>{@code queue:<event>:early}: fans who came before the sale opened, with random scores.
 *   <li>{@code pass:<event>:<fan>}: present while a let-in fan may hold seats (10 minutes).
 * </ul>
 * Nothing has to "move the line" in the background: the number of fans let in so far is just
 * {@code rate × minutes since the sale opened}, so any server can work it out from the clock.
 *
 * <p>Two rules keep it fair:
 * <ul>
 *   <li>Turns nobody used aren't saved up. A fan who joins gets {@code max(last number, let in so
 *       far) + 1}, so if the line was empty for half an hour, a crowd arriving now still goes in
 *       at the rate instead of all at once.
 *   <li>Arriving early doesn't make you first. Everyone who joins before the sale opens is put in
 *       a random order when it opens, so a script that joins in the first millisecond has no edge
 *       over a person who joined five minutes before.
 * </ul>
 */
@Service
public class WaitingRoom {

    static final Duration PASS_TTL = Duration.ofMinutes(10);

    // atomic so two servers can't hand out the same number
    private static final DefaultRedisScript<Long> JOIN = new DefaultRedisScript<>("""
            local have = redis.call('ZSCORE', KEYS[1], ARGV[1])
            if have then return tonumber(have) end
            local n = math.max(tonumber(redis.call('GET', KEYS[2]) or '0'), tonumber(ARGV[2])) + 1
            redis.call('SET', KEYS[2], n)
            redis.call('ZADD', KEYS[1], n, ARGV[1])
            return n""", Long.class);

    // run once when the sale opens: early fans, in their random order, become numbers 1..k
    private static final DefaultRedisScript<Long> OPEN = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[4]) == 1 then return 0 end
            local early = redis.call('ZRANGE', KEYS[3], 0, -1)
            for i, fan in ipairs(early) do redis.call('ZADD', KEYS[1], i, fan) end
            redis.call('SET', KEYS[2], #early)
            redis.call('SET', KEYS[4], '1')
            redis.call('DEL', KEYS[3])
            return #early""", Long.class);

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * @param position place in line among fans not yet let in (1 = next); 0 once let in, or before
     *     the sale opens (early fans are numbered when it opens)
     * @param estimatedWaitSeconds rough wait at the current rate
     * @param opensAt when the sale opens, if it hasn't yet
     */
    public record Status(boolean admitted, long position, long estimatedWaitSeconds, Instant passExpiresAt,
                         Instant opensAt) {}

    private final StringRedisTemplate redis;
    private final EventService events;
    private final Clock clock;

    public WaitingRoom(StringRedisTemplate redis, EventService events, Clock clock) {
        this.redis = redis;
        this.events = events;
        this.clock = clock;
    }

    /** Joins the line (or keeps your place if you're already in it) and says where you are. */
    public Status join(UUID eventId, UUID fan) {
        EventService.Event e = withQueue(eventId);
        if (!isOpen(e)) {
            redis.opsForZSet().addIfAbsent(earlyKey(e), fan.toString(), RANDOM.nextDouble());
            return new Status(false, 0, 0, null, e.onSaleAt());
        }
        open(e);
        redis.execute(JOIN, List.of(lineKey(e), lineKey(e) + ":seq"), fan.toString(), Long.toString(letIn(e)));
        return status(e, fan);
    }

    public Status status(UUID eventId, UUID fan) {
        EventService.Event e = withQueue(eventId);
        if (!isOpen(e)) {
            if (redis.opsForZSet().score(earlyKey(e), fan.toString()) == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "not_in_queue");
            }
            return new Status(false, 0, 0, null, e.onSaleAt());
        }
        open(e);
        return status(e, fan);
    }

    /** True if the fan may hold seats now: the event has no waiting room, or they came through it. */
    public boolean mayBuy(EventService.Event e, UUID fan) {
        return e.queuePerMinute() == null || Boolean.TRUE.equals(redis.hasKey(passKey(e.id(), fan)));
    }

    private Status status(EventService.Event e, UUID fan) {
        String pass = passKey(e.id(), fan);
        Long ttl = redis.getExpire(pass);
        if (ttl != null && ttl > 0) {
            return new Status(true, 0, 0, clock.instant().plusSeconds(ttl), null);
        }
        Double number = redis.opsForZSet().score(lineKey(e), fan.toString());
        if (number == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "not_in_queue");
        }
        long position = number.longValue() - letIn(e);
        if (position <= 0) {
            // their turn has come. One pass per turn: once it runs out they have to join again.
            redis.opsForValue().setIfAbsent(pass, "1", PASS_TTL);
            redis.opsForZSet().remove(lineKey(e), fan.toString());
            return new Status(true, 0, 0, clock.instant().plus(PASS_TTL), null);
        }
        // numbers never change once given, so removing let-in fans doesn't shift anyone's place
        return new Status(false, position, position * 60 / e.queuePerMinute(), null, null);
    }

    /** Fans still waiting in line (or, before the sale opens, waiting for it to open). */
    public long waiting(EventService.Event e) {
        if (e.queuePerMinute() == null || !"ON_SALE".equals(e.status())) {
            return 0;
        }
        if (!isOpen(e)) {
            Long early = redis.opsForZSet().zCard(earlyKey(e));
            return early == null ? 0 : early;
        }
        Long ahead = redis.opsForZSet().count(lineKey(e), letIn(e) + 1, Double.POSITIVE_INFINITY);
        return ahead == null ? 0 : ahead;
    }

    /** How many fans the line has let in so far. */
    long letIn(EventService.Event e) {
        long ms = Math.max(0, Duration.between(e.onSaleAt(), clock.instant()).toMillis());
        return ms * e.queuePerMinute() / 60_000;
    }

    private boolean isOpen(EventService.Event e) {
        return !clock.instant().isBefore(e.onSaleAt());
    }

    private void open(EventService.Event e) {
        redis.execute(OPEN, List.of(lineKey(e), lineKey(e) + ":seq", earlyKey(e), lineKey(e) + ":opened"));
    }

    private EventService.Event withQueue(UUID eventId) {
        EventService.Event e = events.get(eventId);
        if (!"ON_SALE".equals(e.status()) || e.queuePerMinute() == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "no_queue");
        }
        return e;
    }

    private static String lineKey(EventService.Event e) {
        return "queue:" + e.id();
    }

    private static String earlyKey(EventService.Event e) {
        return "queue:" + e.id() + ":early";
    }

    private static String passKey(UUID eventId, UUID fan) {
        return "pass:" + eventId + ":" + fan;
    }
}
