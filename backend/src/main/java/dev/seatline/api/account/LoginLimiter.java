package dev.seatline.api.account;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
    private final Map<String, Deque<Long>> failures = new ConcurrentHashMap<>();

    public LoginLimiter(Clock clock) {
        this.clock = clock;
    }

    public boolean allowed(String email, String address) {
        return recent("e:" + email) < MAX_PER_ACCOUNT && recent("a:" + address) < MAX_PER_ADDRESS;
    }

    public void failed(String email, String address) {
        record("e:" + email);
        record("a:" + address);
    }

    public void succeeded(String email) {
        failures.remove("e:" + email);
    }

    private int recent(String key) {
        Deque<Long> times = failures.get(key);
        if (times == null) {
            return 0;
        }
        synchronized (times) {
            prune(times);
            return times.size();
        }
    }

    private void record(String key) {
        Deque<Long> times = failures.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (times) {
            prune(times);
            times.addLast(clock.millis());
        }
    }

    private void prune(Deque<Long> times) {
        long cutoff = clock.millis() - WINDOW.toMillis();
        while (!times.isEmpty() && times.peekFirst() < cutoff) {
            times.removeFirst();
        }
    }
}
