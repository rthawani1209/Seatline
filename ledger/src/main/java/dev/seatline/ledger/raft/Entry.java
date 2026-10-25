package dev.seatline.ledger.raft;

/**
 * One slot in the replicated log.
 *
 * <p>{@code timeMillis} is stamped by the leader when the entry is proposed. The state machine
 * uses it instead of reading a clock, so every replica makes the same decision about things like
 * hold expiry no matter when it applies the entry.
 *
 * <p>An empty {@code data} array marks the no-op a new leader appends at the start of its term.
 */
public record Entry(long term, long index, long timeMillis, byte[] data) {

    public static final byte[] NOOP = new byte[0];

    public boolean isNoop() {
        return data.length == 0;
    }
}
