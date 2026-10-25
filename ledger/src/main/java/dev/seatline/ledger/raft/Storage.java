package dev.seatline.ledger.raft;

import java.util.List;

/**
 * Durable home for the Raft log, hard state and latest snapshot.
 *
 * <p>Log and hard-state writes may be buffered; nothing counts as durable until {@link #sync()}
 * returns. The node calls sync before it releases any message, so it never promises a peer
 * something it could forget in a crash. {@link #saveSnapshot} is durable when it returns.
 */
public interface Storage {

    HardState hardState();

    void setHardState(HardState state);

    /** Index of the first entry still in the log (everything before is inside the snapshot). */
    long firstIndex();

    long lastIndex();

    /** Term of the entry at {@code index}; also works for the snapshot's last index (0 for an empty log). */
    long term(long index);

    Entry entry(long index);

    /** Entries in [from, toInclusive]. */
    List<Entry> entries(long from, long toInclusive);

    /** Appends entries that must start at {@code lastIndex() + 1}. */
    void append(List<Entry> entries);

    /** Drops the entry at {@code index} and everything after it. */
    void truncateFrom(long index);

    Snapshot snapshot();

    /**
     * Stores a snapshot and drops the log entries it covers. Entries after it are kept when the log
     * agrees with the snapshot at its last index, and discarded otherwise.
     */
    void saveSnapshot(Snapshot snapshot);

    void sync();
}
