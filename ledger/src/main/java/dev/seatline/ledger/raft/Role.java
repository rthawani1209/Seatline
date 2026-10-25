package dev.seatline.ledger.raft;

public enum Role {
    FOLLOWER,
    /** Asking peers whether an election could succeed, without bumping the term yet. */
    PRE_CANDIDATE,
    CANDIDATE,
    LEADER
}
