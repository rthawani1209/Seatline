package dev.seatline.ledger.raft;

/**
 * Timing is counted in ticks so the same code runs on a real clock (one tick every 10 ms in
 * production) and on the simulator's virtual clock.
 */
public record RaftConfig(int electionTicks, int heartbeatTicks, int maxEntriesPerAppend) {

    /** 150-300 ms elections and 50 ms heartbeats at a 10 ms tick. */
    public static final RaftConfig DEFAULT = new RaftConfig(15, 5, 256);

    public RaftConfig {
        if (heartbeatTicks <= 0 || electionTicks <= heartbeatTicks) {
            throw new IllegalArgumentException("election timeout must be longer than the heartbeat");
        }
        if (maxEntriesPerAppend <= 0) {
            throw new IllegalArgumentException("maxEntriesPerAppend must be positive");
        }
    }

    public RaftConfig withMaxEntriesPerAppend(int n) {
        return new RaftConfig(electionTicks, heartbeatTicks, n);
    }
}
