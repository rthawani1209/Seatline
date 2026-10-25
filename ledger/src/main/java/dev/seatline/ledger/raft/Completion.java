package dev.seatline.ledger.raft;

/** Callback for work that finishes later, such as a proposal waiting to commit. */
public interface Completion<T> {

    void complete(T result);

    void fail(RaftException error);
}
