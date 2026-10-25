package dev.seatline.ledger.raft;

import java.util.List;

/**
 * Every message two Raft nodes exchange. Requests and their responses are separate messages so the
 * core never blocks waiting for a reply; the transport (gRPC or the simulator) carries them.
 */
public sealed interface Message {

    int from();

    int to();

    long term();

    /**
     * @param preVote true for the pre-vote round. {@code term} is then the term the sender would
     *     move to, and receivers do not update their own term or vote.
     */
    record RequestVote(int from, int to, long term, long lastLogIndex, long lastLogTerm, boolean preVote)
            implements Message {}

    record VoteResponse(int from, int to, long term, boolean granted, boolean preVote) implements Message {}

    /** @param readSeq leader's latest read round, echoed back so reads can be confirmed (ReadIndex). */
    record AppendEntries(int from, int to, long term, long prevLogIndex, long prevLogTerm,
                         List<Entry> entries, long leaderCommit, long readSeq) implements Message {}

    /**
     * @param matchIndex on success, the last index the follower now shares with the leader
     * @param rejectedPrevIndex on failure, the prevLogIndex of the request being rejected
     * @param conflictTerm on failure, the follower's term at that index, or -1 if its log is too short
     * @param conflictIndex on failure, the first index the leader should try next
     */
    record AppendResponse(int from, int to, long term, boolean success, long matchIndex,
                          long rejectedPrevIndex, long conflictTerm, long conflictIndex, long readSeq)
            implements Message {}
}
