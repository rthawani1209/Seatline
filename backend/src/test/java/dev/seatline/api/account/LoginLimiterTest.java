package dev.seatline.api.account;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class LoginLimiterTest {

    /** A clock the test moves by hand. */
    static final class TestClock extends Clock {
        Instant now = Instant.parse("2026-11-04T12:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void refusesAfterTooManyFailuresAndForgetsThemLater() {
        TestClock clock = new TestClock();
        LoginLimiter limiter = new LoginLimiter(clock);
        for (int i = 0; i < LoginLimiter.MAX_PER_ACCOUNT; i++) {
            limiter.failed("jo@example.com", "10.0.0." + i);
        }
        assertThat(limiter.allowed("jo@example.com", "10.0.0.99")).isFalse();
        assertThat(limiter.allowed("sam@example.com", "10.0.0.99")).isTrue();

        clock.now = clock.now.plus(LoginLimiter.WINDOW).plusSeconds(1);
        assertThat(limiter.allowed("jo@example.com", "10.0.0.99")).isTrue();
    }

    @Test
    void oldKeysAreSweptAway() {
        TestClock clock = new TestClock();
        LoginLimiter limiter = new LoginLimiter(clock);
        // one failure each from many addresses, the shape of a spray attack
        for (int i = 0; i < 500; i++) {
            limiter.failed("user" + i + "@example.com", "10.1." + (i / 256) + "." + (i % 256));
        }
        assertThat(limiter.keys()).isEqualTo(1_000);

        clock.now = clock.now.plus(LoginLimiter.WINDOW).plusSeconds(1);
        for (int i = 0; i < LoginLimiter.SWEEP_EVERY / 2; i++) {
            limiter.failed("late@example.com", "10.9.9.9"); // the 1,000th failure triggers a sweep
        }
        assertThat(limiter.keys()).isEqualTo(2);
    }
}
