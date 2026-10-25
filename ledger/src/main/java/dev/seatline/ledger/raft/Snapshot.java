package dev.seatline.ledger.raft;

/** The state machine's state as of {@code lastIndex}, replacing every log entry up to it. */
public record Snapshot(long lastIndex, long lastTerm, byte[] data) {

    public static final Snapshot EMPTY = new Snapshot(0, 0, new byte[0]);
}
