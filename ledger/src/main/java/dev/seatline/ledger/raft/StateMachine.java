package dev.seatline.ledger.raft;

/** The application the log drives. Must be deterministic: same entries in, same state out. */
public interface StateMachine {

    /** Applies a committed command and returns the result to hand back to the client. */
    byte[] apply(Entry entry);
}
