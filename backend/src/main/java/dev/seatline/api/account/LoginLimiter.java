package dev.seatline.api.account;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Slows down password guessing without letting a stranger lock someone out of their own account.
 * Every sign-in attempt counts against three limits within a 15-minute window:
 * <ul>
 *   <li>one address on one account (5): the usual guesser hits this first, and only they are stopped;
 *   <li>one address on any accounts (30): spraying a common password across many accounts;
 *   <li>one account from any addresses (50): a guesser with many addresses. Kept high, because
 *       reaching it also blocks the owner for a while.
 * </ul>
 * The attempt is counted before the password is checked, so 40 attempts sent at once can't all
 * slip through a "check, then record" gap. A successful sign-in takes its attempt back.
 * Kept in memory: each API instance counts on its own.
 */
@Component
public class LoginLimiter {

    static final int MAX_PER_ADDRESS_AND_ACCOUNT = 5;
    static final int MAX_PER_ADDRESS = 30;
    static final int MAX_PER_ACCOUNT = 50;
    static final Duration WINDOW = Duration.ofMinutes(15);
    static final int SWEEP_EVERY = 1_000;

    private final Clock clock;
    private final Map<String, Deque<Long>> attempts = new ConcurrentHashMap<>();
    private final AtomicLong recorded = new AtomicLong();

    public LoginLimiter(Clock clock) {
        this.clock = clock;
    }

    /** Counts an attempt; false means refuse it without checking the password. */
    public boolean tryAttempt(String email, String address) {
        int pair = record("p:" + address + "|" + email);
        int fromAddress = record("a:" + address);
        int onAccount = record("e:" + email);
        if (recorded.incrementAndGet() % SWEEP_EVERY == 0) {
            sweep();
        }
        return pair <= MAX_PER_ADDRESS_AND_ACCOUNT && fromAddress <= MAX_PER_ADDRESS && onAccount <= MAX_PER_ACCOUNT;
    }

    /** The right password (and code): forget this address's misses on the account, and this attempt. */
    public void succeeded(String email, String address) {
        attempts.remove("p:" + address + "|" + email);
        attempts.computeIfPresent("a:" + address, (k, times) -> {
            times.pollLast();
            return times.isEmpty() ? null : times;
        });
        attempts.computeIfPresent("e:" + email, (k, times) -> {
            times.pollLast();
            return times.isEmpty() ? null : times;
        });
    }

    /** Drops keys whose attempts have all aged out, so the map doesn't grow forever. */
    void sweep() {
        attempts.keySet().forEach(key -> attempts.computeIfPresent(key, (k, times) -> {
            prune(times);
            return times.isEmpty() ? null : times;
        }));
    }

    int keys() {
        return attempts.size();
    }

    // compute runs under the map's lock for that key, so adding and counting is one step
    private int record(String key) {
        int[] count = {0};
        attempts.compute(key, (k, times) -> {
            Deque<Long> t = times != null ? times : new ArrayDeque<>();
            prune(t);
            t.addLast(clock.millis());
            count[0] = t.size();
            return t;
        });
        return count[0];
    }

    private void prune(Deque<Long> times) {
        long cutoff = clock.millis() - WINDOW.toMillis();
        while (!times.isEmpty() && times.peekFirst() < cutoff) {
            times.removeFirst();
        }
    }
}
