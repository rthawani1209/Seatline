package dev.seatline.api.account;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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
    void aGuesserIsStoppedButTheOwnerElsewhereIsNot() {
        TestClock clock = new TestClock();
        LoginLimiter limiter = new LoginLimiter(clock);
        for (int i = 0; i < LoginLimiter.MAX_PER_ADDRESS_AND_ACCOUNT; i++) {
            assertThat(limiter.tryAttempt("jo@example.com", "203.0.113.9")).isTrue();
        }
        assertThat(limiter.tryAttempt("jo@example.com", "203.0.113.9")).isFalse();
        // Jo, at home, can still sign in
        assertThat(limiter.tryAttempt("jo@example.com", "198.51.100.7")).isTrue();

        clock.now = clock.now.plus(LoginLimiter.WINDOW).plusSeconds(1);
        assertThat(limiter.tryAttempt("jo@example.com", "203.0.113.9")).isTrue();
    }

    @Test
    void manyAddressesOnOneAccountOrOneAddressOnManyAccountsAreStoppedToo() {
        LoginLimiter limiter = new LoginLimiter(new TestClock());
        for (int i = 0; i < LoginLimiter.MAX_PER_ACCOUNT; i++) {
            limiter.tryAttempt("jo@example.com", "10.0." + (i / 256) + "." + (i % 256));
        }
        assertThat(limiter.tryAttempt("jo@example.com", "10.9.9.9")).isFalse();

        for (int i = 0; i < LoginLimiter.MAX_PER_ADDRESS; i++) {
            limiter.tryAttempt("user" + i + "@example.com", "203.0.113.50");
        }
        assertThat(limiter.tryAttempt("someone-else@example.com", "203.0.113.50")).isFalse();
    }

    @Test
    void hammeringAwayDoesntKeepTheLimitGoing() {
        TestClock clock = new TestClock();
        LoginLimiter limiter = new LoginLimiter(clock);
        for (int i = 0; i < LoginLimiter.MAX_PER_ADDRESS_AND_ACCOUNT; i++) {
            limiter.tryAttempt("jo@example.com", "203.0.113.9");
        }
        // refused attempts all through the window aren't counted...
        for (int minute = 0; minute < 15; minute++) {
            clock.now = clock.now.plusSeconds(60);
            assertThat(limiter.tryAttempt("jo@example.com", "203.0.113.9")).isFalse();
        }
        // ...so it still ends 15 minutes after the last one let through
        clock.now = clock.now.plusSeconds(1);
        assertThat(limiter.tryAttempt("jo@example.com", "203.0.113.9")).isTrue();
    }

    @Test
    void aStrangerWithManyAddressesCantKeepTheOwnerOut() {
        LoginLimiter limiter = new LoginLimiter(new TestClock());
        assertThat(limiter.tryAttempt("jo@example.com", "198.51.100.7")).isTrue();
        limiter.succeeded("jo@example.com", "198.51.100.7"); // Jo signs in from home

        for (int i = 0; i < LoginLimiter.MAX_PER_ACCOUNT * 3; i++) {
            limiter.tryAttempt("jo@example.com", "10.0." + (i / 256) + "." + (i % 256));
        }
        assertThat(limiter.tryAttempt("jo@example.com", "10.9.9.9")).isFalse();
        // home has signed in before, so the account-wide cap doesn't apply there
        assertThat(limiter.tryAttempt("jo@example.com", "198.51.100.7")).isTrue();
    }

    @Test
    void someoneSprayingFromASharedNetworkDoesntLockOutItsRegulars() {
        LoginLimiter limiter = new LoginLimiter(new TestClock());
        String campus = "203.0.113.80"; // one address for a whole campus
        assertThat(limiter.tryAttempt("jo@example.com", campus)).isTrue();
        limiter.succeeded("jo@example.com", campus);

        for (int i = 0; i < LoginLimiter.MAX_PER_ADDRESS * 2; i++) {
            limiter.tryAttempt("user" + i + "@example.com", campus);
        }
        assertThat(limiter.tryAttempt("newcomer@example.com", campus)).isFalse();
        assertThat(limiter.tryAttempt("jo@example.com", campus)).isTrue();
    }

    @Test
    void resetEmailsHaveTheirOwnBudget() {
        TestClock clock = new TestClock();
        LoginLimiter limiter = new LoginLimiter(clock);
        for (int i = 0; i < LoginLimiter.RESETS_PER_ACCOUNT; i++) {
            assertThat(limiter.tryResetEmail("jo@example.com", "10.0.0." + i)).isTrue();
        }
        assertThat(limiter.tryResetEmail("jo@example.com", "10.0.0.99")).isFalse();
        // asking for resets doesn't stop anyone signing in
        assertThat(limiter.tryAttempt("jo@example.com", "10.0.0.1")).isTrue();

        clock.now = clock.now.plus(LoginLimiter.RESET_WINDOW).plusSeconds(1);
        assertThat(limiter.tryResetEmail("jo@example.com", "10.0.0.99")).isTrue();
    }

    @Test
    void fortyAttemptsAtOnceStillOnlyGetFiveTries() throws Exception {
        LoginLimiter limiter = new LoginLimiter(new TestClock());
        List<Callable<Boolean>> tries = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            tries.add(() -> limiter.tryAttempt("jo@example.com", "203.0.113.9"));
        }
        int allowed = 0;
        try (var pool = Executors.newFixedThreadPool(40)) {
            for (Future<Boolean> f : pool.invokeAll(tries)) {
                allowed += f.get() ? 1 : 0;
            }
        }
        assertThat(allowed).isEqualTo(LoginLimiter.MAX_PER_ADDRESS_AND_ACCOUNT);
    }

    @Test
    void signingInSuccessfullyDoesntUseUpTheAddressesAllowance() {
        LoginLimiter limiter = new LoginLimiter(new TestClock());
        // a whole office behind one address signs in
        for (int i = 0; i < 200; i++) {
            String email = "staff" + i + "@example.com";
            assertThat(limiter.tryAttempt(email, "203.0.113.1")).isTrue();
            limiter.succeeded(email, "203.0.113.1");
        }
    }

    @Test
    void oldKeysAreSweptAway() {
        TestClock clock = new TestClock();
        LoginLimiter limiter = new LoginLimiter(clock);
        // one attempt each from many addresses, the shape of a spray attack
        for (int i = 0; i < 300; i++) {
            limiter.tryAttempt("user" + i + "@example.com", "10.1." + (i / 256) + "." + (i % 256));
        }
        assertThat(limiter.keys()).isEqualTo(900);

        clock.now = clock.now.plus(LoginLimiter.WINDOW).plusSeconds(1);
        for (int i = 0; i < LoginLimiter.SWEEP_EVERY - 300; i++) {
            limiter.tryAttempt("late@example.com", "10.9.9.9"); // the 1,000th attempt triggers a sweep
        }
        assertThat(limiter.keys()).isEqualTo(3); // just the late attempts' keys are left
    }
}
