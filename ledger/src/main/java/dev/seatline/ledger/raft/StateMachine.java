package dev.seatline.ledger.raft;

/** The application the log drives. Must be deterministic: same entries in, same state out. */
public interface StateMachine {

    /** Applies a committed command and returns the result to hand back to the client. */
    byte[] apply(Entry entry);

    /** Serializes the whole state, for log compaction and for catching up slow followers. */
    byte[] snapshot();

    /** Replaces the whole state with a snapshot taken by {@link #snapshot()}. */
    void restore(byte[] snapshot);

    /**
     * Checked by the leader before a command goes into the log, so obviously broken requests are
     * turned away up front. {@link #apply} must still cope with anything, because the log can hold
     * entries written by an older version of the code.
     */
    default boolean accepts(byte[] command) {
        return command.length > 0;
    }
}
