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
 * Slows down password guessing: after too many failed logins for one email, or from one address,
 * within a window, further attempts are refused until old failures age out. Kept in memory for now;
 * it moves to Redis with the waiting room so every API instance shares the counts.
 */
@Component
public class LoginLimiter {

    static final int MAX_PER_ACCOUNT = 5;
    static final int MAX_PER_ADDRESS = 20;
    static final Duration WINDOW = Duration.ofMinutes(15);

    private final Clock clock;
    static final int SWEEP_EVERY = 1_000;

    private final Map<String, Deque<Long>> failures = new ConcurrentHashMap<>();
    private final AtomicLong recorded = new AtomicLong();

    public LoginLimiter(Clock clock) {
        this.clock = clock;
    }

    public boolean allowed(String email, String address) {
        return recent("e:" + email) < MAX_PER_ACCOUNT && recent("a:" + address) < MAX_PER_ADDRESS;
    }

    public void failed(String email, String address) {
        record("e:" + email);
        record("a:" + address);
        if (recorded.incrementAndGet() % SWEEP_EVERY == 0) {
            sweep();
        }
    }

    public void succeeded(String email) {
        failures.remove("e:" + email);
    }

    /** Drops keys whose failures have all aged out, so the map doesn't grow forever. */
    void sweep() {
        failures.keySet().forEach(this::recent);
    }

    int keys() {
        return failures.size();
    }

    // compute/computeIfPresent run under the map's own lock for that key, so pruning, counting and
    // removing an empty entry can't race with another thread adding a failure to it
    private int recent(String key) {
        int[] count = {0};
        failures.computeIfPresent(key, (k, times) -> {
            prune(times);
            count[0] = times.size();
            return times.isEmpty() ? null : times;
        });
        return count[0];
    }

    private void record(String key) {
        failures.compute(key, (k, times) -> {
            Deque<Long> t = times != null ? times : new ArrayDeque<>();
            prune(t);
            t.addLast(clock.millis());
            return t;
        });
    }

    private void prune(Deque<Long> times) {
        long cutoff = clock.millis() - WINDOW.toMillis();
        while (!times.isEmpty() && times.peekFirst() < cutoff) {
            times.removeFirst();
        }
    }
}
