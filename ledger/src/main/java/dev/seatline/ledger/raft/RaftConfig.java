package dev.seatline.ledger.raft;

/**
 * Timing is counted in ticks so the same code runs on a real clock (one tick every 10 ms in
 * production) and on the simulator's virtual clock.
 *
 * @param checkQuorum leaders step down after an election timeout without hearing from a majority.
 *     Always on in production; tests turn it off to keep a cut-off leader believing it still leads.
 */
public record RaftConfig(int electionTicks, int heartbeatTicks, int maxEntriesPerAppend, int snapshotEvery,
                         boolean checkQuorum) {

    /** 150-300 ms elections and 50 ms heartbeats at a 10 ms tick; snapshot every 10k entries. */
    public static final RaftConfig DEFAULT = new RaftConfig(15, 5, 256, 10_000, true);

    public RaftConfig {
        if (heartbeatTicks <= 0 || electionTicks <= heartbeatTicks) {
            throw new IllegalArgumentException("election timeout must be longer than the heartbeat");
        }
        if (maxEntriesPerAppend <= 0) {
            throw new IllegalArgumentException("maxEntriesPerAppend must be positive");
        }
        if (snapshotEvery <= 0) {
            throw new IllegalArgumentException("snapshotEvery must be positive");
        }
    }

    public RaftConfig withMaxEntriesPerAppend(int n) {
        return new RaftConfig(electionTicks, heartbeatTicks, n, snapshotEvery, checkQuorum);
    }

    public RaftConfig withSnapshotEvery(int n) {
        return new RaftConfig(electionTicks, heartbeatTicks, maxEntriesPerAppend, n, checkQuorum);
    }

    public RaftConfig withCheckQuorum(boolean on) {
        return new RaftConfig(electionTicks, heartbeatTicks, maxEntriesPerAppend, snapshotEvery, on);
    }
}
