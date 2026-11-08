package dev.seatline.api.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;

/**
 * A per-minute request budget shared by every API server (it counts in Redis). Fixed one-minute
 * windows: simple, and close enough for "stop a script hammering an endpoint".
 */
@Component
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    private static final DefaultRedisScript<Long> COUNT = new DefaultRedisScript<>("""
            local n = redis.call('INCR', KEYS[1])
            if n == 1 then redis.call('EXPIRE', KEYS[1], 61) end
            return n""", Long.class);

    private final StringRedisTemplate redis;
    private final Clock clock;

    public RateLimiter(StringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    /**
     * Counts one request against {@code what} and refuses it (429) once the minute's budget is spent.
     * If Redis can't be reached the request goes through: losing the limit for a moment is better
     * than refusing every fan in the middle of a sale.
     */
    public void spend(String what, int perMinute) {
        long minute = clock.millis() / 60_000;
        Long n;
        try {
            n = redis.execute(COUNT, List.of("rate:" + what + ":" + minute));
        } catch (DataAccessException e) {
            log.warn("rate limit not checked, Redis unavailable: {}", e.toString());
            return;
        }
        if (n != null && n > perMinute) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "too_many_attempts");
        }
    }
}
