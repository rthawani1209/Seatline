package dev.seatline.ledger.raft;

/** Storage the simulator can pull the plug on. */
public interface CrashableStorage extends Storage {

    /** Forgets everything written since the last {@link #sync()}, like a power cut would. */
    void losePower();
}
