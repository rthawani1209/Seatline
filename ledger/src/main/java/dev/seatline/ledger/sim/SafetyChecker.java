package dev.seatline.ledger.sim;

import dev.seatline.ledger.raft.Entry;
import dev.seatline.ledger.raft.RaftObserver;
import dev.seatline.ledger.raft.Role;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Watches every node and throws the moment one of Raft's safety guarantees breaks:
 * at most one leader per term (election safety), and no two nodes ever apply different entries at
 * the same index (state machine safety).
 */
public final class SafetyChecker implements RaftObserver {

    private final long seed;
    final Map<Long, Integer> leaders = new HashMap<>();
    final TreeMap<Long, Entry> applied = new TreeMap<>();

    public SafetyChecker(long seed) {
        this.seed = seed;
    }

    @Override
    public void roleChanged(int node, Role role, long term, int leader) {
        if (role != Role.LEADER) {
            return;
        }
        Integer existing = leaders.putIfAbsent(term, node);
        if (existing != null && existing != node) {
            throw new SafetyViolation(seed, "two leaders in term " + term + ": node " + existing + " and node " + node);
        }
    }

    @Override
    public void applied(int node, Entry entry) {
        Entry first = applied.putIfAbsent(entry.index(), entry);
        if (first != null && (first.term() != entry.term() || !Arrays.equals(first.data(), entry.data()))) {
            throw new SafetyViolation(seed, "node " + node + " applied a different entry at index "
                    + entry.index() + " (term " + entry.term() + ", another node applied term " + first.term() + ")");
        }
    }

    public static final class SafetyViolation extends AssertionError {
        public SafetyViolation(long seed, String message) {
            super(message + " [replay with seed " + seed + "]");
        }
    }
}
