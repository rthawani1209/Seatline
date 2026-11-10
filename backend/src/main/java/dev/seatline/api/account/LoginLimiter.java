package dev.seatline.api.account;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Slows down password guessing without letting a stranger lock someone out of their own account.
 * Sign-in attempts count against three limits within a 15-minute window:
 * <ul>
 *   <li>one address on one account (5): the usual guesser hits this first, and only they are stopped;
 *   <li>one address on any accounts (30): spraying a common password across many accounts;
 *   <li>one account from any addresses (50): a guesser with many addresses.
 * </ul>
 * The last two are soft: they don't apply to an address that has signed in to the account in the
 * last 30 days, so a stranger hitting them can't keep the owner out, even from a shared network.
 * A refused attempt isn't counted, so hammering away doesn't stretch the window: it ends 15 minutes
 * after the last attempt that was let through. Checking and counting happen in one step, so 40
 * attempts sent at once can't all slip through. A successful sign-in takes its attempt back.
 * Reset emails have their own, separate budget. Kept in memory: each API instance counts on its own.
 */
@Component
public class LoginLimiter {

    static final int MAX_PER_ADDRESS_AND_ACCOUNT = 5;
    static final int MAX_PER_ADDRESS = 30;
    static final int MAX_PER_ACCOUNT = 50;
    static final Duration WINDOW = Duration.ofMinutes(15);
    static final Duration KNOWN_FOR = Duration.ofDays(30);
    static final int RESETS_PER_ACCOUNT = 3;
    static final int RESETS_PER_ADDRESS = 10;
    static final Duration RESET_WINDOW = Duration.ofHours(1);
    static final int SWEEP_EVERY = 1_000;

    private final Clock clock;
    private final Map<String, Deque<Long>> attempts = new HashMap<>();
    private final Map<String, Long> knownAddresses = new HashMap<>();
    private long calls;

    public LoginLimiter(Clock clock) {
        this.clock = clock;
    }

    /** Counts an attempt; false means refuse it without checking the password. */
    public synchronized boolean tryAttempt(String email, String address) {
        sweepNowAndThen();
        String pair = "p:" + address + "|" + email, fromAddress = "a:" + address, onAccount = "e:" + email;
        boolean known = isKnown(email, address);
        if (count(pair, WINDOW) >= MAX_PER_ADDRESS_AND_ACCOUNT
                || (!known && count(fromAddress, WINDOW) >= MAX_PER_ADDRESS)
                || (!known && count(onAccount, WINDOW) >= MAX_PER_ACCOUNT)) {
            return false;
        }
        add(pair);
        add(fromAddress);
        add(onAccount);
        return true;
    }

    /** The right password (and code): forget this address's misses on the account, and this attempt. */
    public synchronized void succeeded(String email, String address) {
        attempts.remove("p:" + address + "|" + email);
        takeBack("a:" + address);
        takeBack("e:" + email);
        knownAddresses.put(address + "|" + email, clock.millis());
    }

    /** One more reset email for this account from this address; false means don't send it. */
    public synchronized boolean tryResetEmail(String email, String address) {
        sweepNowAndThen();
        String onAccount = "r:" + email, fromAddress = "ra:" + address;
        if (count(onAccount, RESET_WINDOW) >= RESETS_PER_ACCOUNT || count(fromAddress, RESET_WINDOW) >= RESETS_PER_ADDRESS) {
            return false;
        }
        add(onAccount);
        add(fromAddress);
        return true;
    }

    /** Drops keys whose attempts have all aged out, so the maps don't grow forever. */
    synchronized void sweep() {
        attempts.entrySet().removeIf(e -> {
            prune(e.getKey(), e.getValue());
            return e.getValue().isEmpty();
        });
        long cutoff = clock.millis() - KNOWN_FOR.toMillis();
        knownAddresses.values().removeIf(at -> at < cutoff);
    }

    synchronized int keys() {
        return attempts.size();
    }

    private void sweepNowAndThen() {
        if (++calls % SWEEP_EVERY == 0) {
            sweep();
        }
    }

    private boolean isKnown(String email, String address) {
        Long at = knownAddresses.get(address + "|" + email);
        return at != null && at >= clock.millis() - KNOWN_FOR.toMillis();
    }

    private int count(String key, Duration window) {
        Deque<Long> times = attempts.get(key);
        if (times == null) {
            return 0;
        }
        prune(times, window);
        return times.size();
    }

    private void add(String key) {
        attempts.computeIfAbsent(key, k -> new ArrayDeque<>()).addLast(clock.millis());
    }

    private void takeBack(String key) {
        Deque<Long> times = attempts.get(key);
        if (times != null) {
            times.pollLast();
            if (times.isEmpty()) {
                attempts.remove(key);
            }
        }
    }

    private void prune(String key, Deque<Long> times) {
        prune(times, key.startsWith("r") ? RESET_WINDOW : WINDOW);
    }

    private void prune(Deque<Long> times, Duration window) {
        long cutoff = clock.millis() - window.toMillis();
        while (!times.isEmpty() && times.peekFirst() < cutoff) {
            times.removeFirst();
        }
    }
}
