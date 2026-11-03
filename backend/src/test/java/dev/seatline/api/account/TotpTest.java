package dev.seatline.api.account;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class TotpTest {

    // RFC 6238 appendix B: the ASCII secret "12345678901234567890", base32 encoded
    static final String RFC_SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

    @Test
    void matchesTheRfcTestVectors() {
        // the RFC lists 8-digit codes; apps show the last 6
        assertThat(Totp.code(RFC_SECRET, 59 / 30)).isEqualTo("287082");
        assertThat(Totp.code(RFC_SECRET, 1111111109L / 30)).isEqualTo("081804");
        assertThat(Totp.code(RFC_SECRET, 2000000000L / 30)).isEqualTo("279037");
    }

    @Test
    void acceptsOneStepOfDriftAndNoMore() {
        Clock clock = Clock.fixed(Instant.ofEpochSecond(30 * 1000), ZoneOffset.UTC);
        String secret = Totp.newSecret();
        assertThat(Totp.match(secret, Totp.code(secret, 1000), clock)).isEqualTo(1000);
        assertThat(Totp.match(secret, Totp.code(secret, 999), clock)).isEqualTo(999);
        assertThat(Totp.match(secret, Totp.code(secret, 1001), clock)).isEqualTo(1001);
        assertThat(Totp.match(secret, Totp.code(secret, 998), clock)).isEqualTo(-1);
        assertThat(Totp.match(secret, "12345", clock)).isEqualTo(-1);
    }
}
