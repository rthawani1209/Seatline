package dev.seatline.ledger.raft;

import java.util.List;

/**
 * Durable home for the Raft log and hard state.
 *
 * <p>Writes may be buffered; nothing counts as durable until {@link #sync()} returns. The node
 * calls sync before it releases any message, so it never promises a peer something it could forget
 * in a crash.
 */
public interface Storage {

    HardState hardState();

    void setHardState(HardState state);

    /** Index of the first entry still in the log (everything before was compacted into a snapshot). */
    long firstIndex();

    long lastIndex();

    /** Term of the entry at {@code index}; 0 for index 0. */
    long term(long index);

    Entry entry(long index);

    /** Entries in [from, toInclusive]. */
    List<Entry> entries(long from, long toInclusive);

    /** Appends entries that must start at {@code lastIndex() + 1}. */
    void append(List<Entry> entries);

    /** Drops the entry at {@code index} and everything after it. */
    void truncateFrom(long index);

    void sync();
}
