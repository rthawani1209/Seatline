package dev.seatline.ledger.check;

/**
 * One client operation as observed from outside: what was asked, what came back, and when.
 *
 * @param output null when the client never learned the outcome (it timed out); such an operation
 *     may or may not have taken effect, and {@code returned} should be {@link Long#MAX_VALUE}
 */
public record Operation(int client, Object input, Object output, long invoked, long returned) {

    public static Operation unknown(int client, Object input, long invoked) {
        return new Operation(client, input, null, invoked, Long.MAX_VALUE);
    }

    public boolean outcomeUnknown() {
        return output == null;
    }
}
