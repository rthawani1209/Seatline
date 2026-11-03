package dev.seatline.api.account;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Clock;

/**
 * Time-based one-time codes (RFC 6238), the six digits an authenticator app shows. Written out
 * because it's 40 lines: HMAC-SHA1 of the current 30-second step, truncated to six digits.
 */
public final class Totp {

    private static final int STEP_SECONDS = 30;
    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final SecureRandom RANDOM = new SecureRandom();

    private Totp() {}

    /** A new random 160-bit secret, base32 encoded as authenticator apps expect. */
    public static String newSecret() {
        byte[] bytes = new byte[20];
        RANDOM.nextBytes(bytes);
        StringBuilder out = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : bytes) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                out.append(BASE32.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        return out.toString(); // 160 bits is a whole number of 5-bit groups
    }

    public static String uri(String secret, String account) {
        return "otpauth://totp/Seatline:" + account + "?secret=" + secret + "&issuer=Seatline";
    }

    public static long currentStep(Clock clock) {
        return clock.millis() / 1000 / STEP_SECONDS;
    }

    /**
     * Returns the time step the code matches (allowing one step of clock drift either way), or -1.
     * Callers reject steps at or before the last one used, so a code can't be replayed.
     */
    public static long match(String secret, String code, Clock clock) {
        if (code == null || !code.matches("\\d{6}")) {
            return -1;
        }
        long now = currentStep(clock);
        for (long step = now - 1; step <= now + 1; step++) {
            if (constantTimeEquals(code(secret, step), code)) {
                return step;
            }
        }
        return -1;
    }

    static String code(String secret, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(decode(secret), "HmacSHA1"));
            byte[] h = mac.doFinal(java.nio.ByteBuffer.allocate(8).putLong(step).array());
            int offset = h[h.length - 1] & 0xf;
            int bin = ((h[offset] & 0x7f) << 24) | ((h[offset + 1] & 0xff) << 16)
                    | ((h[offset + 2] & 0xff) << 8) | (h[offset + 3] & 0xff);
            return String.format("%06d", bin % 1_000_000);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] decode(String secret) {
        byte[] out = new byte[secret.length() * 5 / 8];
        int buffer = 0;
        int bits = 0;
        int i = 0;
        for (char c : secret.toCharArray()) {
            buffer = (buffer << 5) | BASE32.indexOf(c);
            bits += 5;
            if (bits >= 8) {
                out[i++] = (byte) (buffer >> (bits - 8));
                bits -= 8;
            }
        }
        return out;
    }

    private static boolean constantTimeEquals(String a, String b) {
        return java.security.MessageDigest.isEqual(a.getBytes(), b.getBytes());
    }
}
