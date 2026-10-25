package dev.seatline.ledger.raft;

import java.util.ArrayList;
import java.util.List;

/**
 * In-memory storage that still models durability: writes land in a pending buffer and only become
 * "on disk" at {@link #sync()}. {@link #crash()} throws the buffer away, which is what a power cut
 * would do to unsynced writes.
 */
public final class MemoryStorage implements Storage {

    // durable copy
    private HardState durableHard = HardState.INITIAL;
    private final List<Entry> durableLog = new ArrayList<>();

    // working copy (what the node sees)
    private HardState hard = HardState.INITIAL;
    private final List<Entry> log = new ArrayList<>();
    private boolean dirty;

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
        return 1;
    }

    @Override
    public long lastIndex() {
        return log.size();
    }

    @Override
    public long term(long index) {
        if (index == 0) {
            return 0;
        }
        return entry(index).term();
    }

    @Override
    public Entry entry(long index) {
        if (index < 1 || index > log.size()) {
            throw new IndexOutOfBoundsException("no entry " + index + ", last is " + log.size());
        }
        return log.get((int) (index - 1));
    }

    @Override
    public List<Entry> entries(long from, long toInclusive) {
        if (from > toInclusive) {
            return List.of();
        }
        return List.copyOf(log.subList((int) (from - 1), (int) toInclusive));
    }

    @Override
    public void append(List<Entry> entries) {
        for (Entry e : entries) {
            if (e.index() != log.size() + 1) {
                throw new IllegalArgumentException("gap in log: appending " + e.index() + " after " + log.size());
            }
            log.add(e);
        }
        dirty |= !entries.isEmpty();
    }

    @Override
    public void truncateFrom(long index) {
        while (log.size() >= index) {
            log.removeLast();
        }
        dirty = true;
    }

    @Override
    public void sync() {
        if (!dirty) {
            return;
        }
        durableHard = hard;
        durableLog.clear();
        durableLog.addAll(log);
        dirty = false;
    }

    /** Simulates losing power: anything not synced is gone. */
    public void crash() {
        hard = durableHard;
        log.clear();
        log.addAll(durableLog);
        dirty = false;
    }
}
