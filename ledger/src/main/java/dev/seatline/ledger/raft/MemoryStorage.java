package dev.seatline.ledger.raft;

import java.util.List;

/**
 * In-memory storage that still models durability: the node works on a live copy, and only
 * {@link #sync()} copies it to the "disk" copy. {@link #losePower()} throws away anything unsynced.
 */
public final class MemoryStorage implements CrashableStorage {

    private final LogBuffer log = new LogBuffer();
    private HardState hard = HardState.INITIAL;
    private boolean dirty;

    // what would survive a power cut
    private final LogBuffer durableLog = new LogBuffer();
    private HardState durableHard = HardState.INITIAL;

    @Override
    public HardState hardState() {
        return hard;
    }

    @Override
    public void setHardState(HardState state) {
        hard = state;
        dirty = true;
    }

    @Override
    public long firstIndex() {
        return log.firstIndex();
    }

    @Override
    public long lastIndex() {
        return log.lastIndex();
    }

    @Override
    public long term(long index) {
        return log.term(index);
    }

    @Override
    public Entry entry(long index) {
        return log.entry(index);
    }

    @Override
    public List<Entry> entries(long from, long toInclusive) {
        return log.entries(from, toInclusive);
    }

    @Override
    public void append(List<Entry> entries) {
        for (Entry e : entries) {
            log.append(e);
        }
        dirty |= !entries.isEmpty();
    }

    @Override
    public void truncateFrom(long index) {
        log.truncateFrom(index);
        dirty = true;
    }

    @Override
    public Snapshot snapshot() {
        return log.snapshot();
    }

    @Override
    public void saveSnapshot(Snapshot snapshot) {
        log.installSnapshot(snapshot);
        // snapshots are written straight through, together with everything before them
        dirty = true;
        sync();
    }

    @Override
    public void sync() {
        if (!dirty) {
            return;
        }
        durableHard = hard;
        durableLog.reset(log.snapshot(), log.all());
        dirty = false;
    }

    @Override
    public void losePower() {
        hard = durableHard;
        log.reset(durableLog.snapshot(), durableLog.all());
        dirty = false;
    }
}
