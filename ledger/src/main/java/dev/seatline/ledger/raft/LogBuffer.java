package dev.seatline.ledger.raft;

import java.util.ArrayList;
import java.util.List;

/**
 * The in-memory view of the log shared by both storage implementations: a snapshot followed by
 * the entries after it. Index arithmetic lives here so it's written (and tested) once.
 */
final class LogBuffer {

    private Snapshot snapshot = Snapshot.EMPTY;
    private final ArrayList<Entry> entries = new ArrayList<>();

    Snapshot snapshot() {
        return snapshot;
    }

    long firstIndex() {
        return snapshot.lastIndex() + 1;
    }

    long lastIndex() {
        return snapshot.lastIndex() + entries.size();
    }

    long term(long index) {
        if (index == snapshot.lastIndex()) {
            return snapshot.lastTerm();
        }
        return entry(index).term();
    }

    Entry entry(long index) {
        if (index < firstIndex() || index > lastIndex()) {
            throw new IndexOutOfBoundsException(
                    "no entry " + index + " (log holds " + firstIndex() + ".." + lastIndex() + ")");
        }
        return entries.get((int) (index - firstIndex()));
    }

    List<Entry> entries(long from, long toInclusive) {
        if (from > toInclusive) {
            return List.of();
        }
        if (from < firstIndex() || toInclusive > lastIndex()) {
            throw new IndexOutOfBoundsException("range " + from + ".." + toInclusive + " outside "
                    + firstIndex() + ".." + lastIndex());
        }
        int base = (int) firstIndex();
        return List.copyOf(entries.subList((int) from - base, (int) toInclusive - base + 1));
    }

    List<Entry> all() {
        return List.copyOf(entries);
    }

    void append(Entry e) {
        if (e.index() != lastIndex() + 1) {
            throw new IllegalArgumentException("gap in log: appending " + e.index() + " after " + lastIndex());
        }
        entries.add(e);
    }

    void truncateFrom(long index) {
        if (index < firstIndex()) {
            throw new IllegalArgumentException("can't truncate into the snapshot at " + index);
        }
        while (lastIndex() >= index) {
            entries.removeLast();
        }
    }

    /**
     * Installs a snapshot. If the log already holds the entry the snapshot ends on, the entries after
     * it are kept; otherwise the log is discarded (it disagrees with the snapshot or is too short).
     */
    void installSnapshot(Snapshot s) {
        if (s.lastIndex() <= snapshot.lastIndex()) {
            return;
        }
        List<Entry> keep = List.of();
        if (s.lastIndex() < lastIndex() && term(s.lastIndex()) == s.lastTerm()) {
            keep = entries(s.lastIndex() + 1, lastIndex());
        }
        entries.clear();
        entries.addAll(keep);
        snapshot = s;
    }

    void reset(Snapshot s, List<Entry> es) {
        snapshot = s;
        entries.clear();
        entries.addAll(es);
    }
}
