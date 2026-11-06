package dev.seatline.api.ticket;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;

/**
 * What goes in a ticket's QR code: {@code SL1.<ticket id>.<version>.<step>.<signature>}.
 * <ul>
 *   <li>The signature is an HMAC over everything before it, so nobody can make up or edit a code.
 *   <li>The version goes up when a ticket is transferred, so the old owner's code stops working.
 *   <li>The step is the current 15-second window. The app fetches a fresh code as it rolls over and
 *       the door accepts only the current step or one either side, so a screenshot sent to
 *       someone else goes stale within about half a minute.
 * </ul>
 */
@Component
public class TicketCodes {

    static final long STEP_SECONDS = 15;

    public record Parsed(String ticketId, int version, long step) {}

    public static long step(Instant at) {
        return at.getEpochSecond() / STEP_SECONDS;
    }

    private final SecretKey key;

    public TicketCodes(SecretKey jwtKey) {
        // a key of its own for tickets, derived from the main secret
        this.key = new SecretKeySpec(hmac(jwtKey, "seatline ticket codes"), "HmacSHA256");
    }

    public String code(String ticketId, int version, Instant at) {
        String body = ticketId + "." + version + "." + step(at);
        return "SL1." + body + "." + sign(body);
    }

    /** The ticket and version if the code is one we made, otherwise null. */
    public Parsed parse(String code) {
        if (code == null || !code.startsWith("SL1.")) {
            return null;
        }
        int lastDot = code.lastIndexOf('.');
        String body = code.substring(4, Math.max(4, lastDot));
        int stepDot = body.lastIndexOf('.');
        int versionDot = stepDot <= 0 ? -1 : body.lastIndexOf('.', stepDot - 1);
        if (lastDot <= 4 || versionDot <= 0) {
            return null;
        }
        byte[] given = code.substring(lastDot + 1).getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(given, sign(body).getBytes(StandardCharsets.US_ASCII))) {
            return null;
        }
        try {
            return new Parsed(body.substring(0, versionDot), Integer.parseInt(body.substring(versionDot + 1, stepDot)),
                    Long.parseLong(body.substring(stepDot + 1)));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String sign(String body) {
        // 128 bits is plenty and keeps the QR code small
        return Base64.getUrlEncoder().withoutPadding().encodeToString(Arrays.copyOf(hmac(key, body), 16));
    }

    private static byte[] hmac(SecretKey key, String text) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return mac.doFinal(text.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
