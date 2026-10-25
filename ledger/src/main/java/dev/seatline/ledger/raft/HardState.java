package dev.seatline.ledger.raft;

/** The part of a node's state that must survive a crash, besides the log itself. */
public record HardState(long term, int votedFor) {

    public static final int NONE = -1;
    public static final HardState INITIAL = new HardState(0, NONE);
}
