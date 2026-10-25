package dev.seatline.ledger.raft;

public class RaftException extends RuntimeException {

    public RaftException(String message) {
        super(message);
    }

    /** This node isn't the leader. {@code leaderHint} is who it thinks is, or -1. */
    public static final class NotLeader extends RaftException {
        private final int leaderHint;

        public NotLeader(int leaderHint) {
            super("not the leader" + (leaderHint >= 0 ? ", try node " + leaderHint : ""));
            this.leaderHint = leaderHint;
        }

        public int leaderHint() {
            return leaderHint;
        }
    }

    /**
     * Leadership was lost before the entry committed. The entry may or may not commit later, so the
     * caller has to retry with the same request id (the state machine deduplicates it).
     */
    public static final class OutcomeUnknown extends RaftException {
        public OutcomeUnknown(String message) {
            super(message);
        }
    }
}
