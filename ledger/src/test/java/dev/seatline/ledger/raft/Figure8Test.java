package dev.seatline.ledger.raft;

import dev.seatline.ledger.raft.Message.AppendEntries;
import dev.seatline.ledger.raft.Message.RequestVote;
import dev.seatline.ledger.raft.Message.VoteResponse;
import dev.seatline.ledger.sim.ChecksumStateMachine;
import dev.seatline.ledger.sim.SafetyChecker;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Replays Figure 8 of the Raft paper step by step, delivering each message by hand.
 *
 * <p>The danger: a leader finds an entry from an older term stored on a majority and counts it as
 * committed. A later leader can still legally overwrite that entry, so a node that already applied
 * it disagrees with everyone else. The commit rule's "only count entries from my own term" check
 * prevents this; delete it and this test fails with a safety violation.
 */
class Figure8Test {

    private static final int[] MEMBERS = {1, 2, 3, 4, 5};
    private static final RaftConfig CONFIG = RaftConfig.DEFAULT.withMaxEntriesPerAppend(1);

    private final SafetyChecker safety = new SafetyChecker(0);
    private final Map<Integer, MemoryStorage> disks = new HashMap<>();
    private final Map<Integer, RaftNode> nodes = new HashMap<>();
    private final List<Message> wire = new ArrayList<>();

    private static Entry e(long term, long index, String data) {
        return new Entry(term, index, 0, data.getBytes(StandardCharsets.UTF_8));
    }

    private void disk(int id, long term, int votedFor, Entry... log) {
        MemoryStorage s = new MemoryStorage();
        s.append(List.of(log));
        s.setHardState(new HardState(term, votedFor));
        s.sync();
        disks.put(id, s);
    }

    private void boot(int id) {
        nodes.put(id, new RaftNode(id, MEMBERS, CONFIG, disks.get(id), new ChecksumStateMachine(),
                new Random(id), () -> 0, safety));
    }

    private void collect(int id) {
        wire.addAll(nodes.get(id).drainMessages());
    }

    /** Delivers matching messages (and whatever they cause, if it also matches) until none are left. */
    private void deliver(Predicate<Message> which) {
        boolean progress = true;
        while (progress) {
            progress = false;
            for (int i = 0; i < wire.size(); i++) {
                Message m = wire.get(i);
                if (which.test(m)) {
                    wire.remove(i);
                    nodes.get(m.to()).step(m);
                    collect(m.to());
                    progress = true;
                    break;
                }
            }
        }
    }

    private void drop(Predicate<Message> which) {
        wire.removeIf(which);
    }

    private void tickUntil(int id, Role role) {
        for (int i = 0; i < 10 * CONFIG.electionTicks() && nodes.get(id).role() != role; i++) {
            nodes.get(id).tick();
        }
        assertThat(nodes.get(id).role()).isEqualTo(role);
        collect(id);
    }

    private static Predicate<Message> among(Set<Integer> ids) {
        return m -> ids.contains(m.from()) && ids.contains(m.to());
    }

    private static boolean isVote(Message m) {
        return m instanceof RequestVote || m instanceof VoteResponse;
    }

    @Test
    void oldTermEntryOnMajorityIsNotCommittedByCounting() {
        // Starting point, matching the paper's Figure 8 after step (b):
        // S1 led term 2 and got entry 2 onto S2. S5 then led term 3 and wrote its own entry 2.
        Entry first = e(1, 1, "everyone has this");
        disk(1, 3, HardState.NONE, first, e(2, 2, "from S1 in term 2"));
        disk(2, 3, HardState.NONE, first, e(2, 2, "from S1 in term 2"));
        disk(3, 3, 5, first);
        disk(4, 3, 5, first);
        disk(5, 3, 5, first, e(3, 2, "from S5 in term 3"));
        for (int id = 1; id <= 4; id++) {
            boot(id); // S5 stays down for now
        }

        // (c) S1 wins term 4 with votes from S2, S3 and S4.
        tickUntil(1, Role.PRE_CANDIDATE);
        deliver(m -> isVote(m) && among(Set.of(1, 2, 3, 4)).test(m));
        assertThat(nodes.get(1).role()).isEqualTo(Role.LEADER);
        assertThat(nodes.get(1).term()).isEqualTo(4);

        // S1 copies its old term-2 entry onto S3 and S4, so a majority (S1, S3, S4) now holds it.
        // S2 hears nothing more, and S1's new term-4 entry never lands anywhere: S3 and S4 reject it
        // while they're missing index 2, and we hold it back once they have it.
        drop(m -> m.to() == 2 || m.to() == 5);
        deliver(m -> among(Set.of(1, 3, 4)).test(m)
                && !(m instanceof AppendEntries ae && disks.get(ae.to()).lastIndex() >= 2
                        && ae.entries().stream().anyMatch(x -> x.index() >= 3)));
        assertThat(disks.get(3).term(2)).isEqualTo(2);
        assertThat(disks.get(4).term(2)).isEqualTo(2);

        // The rule under test: entry 2 is on a majority but it's from term 2, so it's NOT committed.
        assertThat(nodes.get(1).commitIndex()).isLessThan(2);

        // (d) S1 crashes. S5 comes back with a term-3 entry at index 2, which beats the term-2 copies,
        // so S2, S3 and S4 elect it and it overwrites index 2 everywhere. That's legal precisely
        // because entry 2 was never committed.
        nodes.remove(1);
        drop(m -> m.from() == 1 || m.to() == 1);
        boot(5);
        Set<Integer> rest = Set.of(2, 3, 4, 5);
        for (int id : List.of(2, 3, 4)) {
            for (int i = 0; i < CONFIG.electionTicks(); i++) {
                nodes.get(id).tick(); // let them notice S1 is gone
            }
            nodes.get(id).drainMessages();
        }
        for (int round = 0; round < 3 && nodes.get(5).role() != Role.LEADER; round++) {
            tickUntil(5, Role.PRE_CANDIDATE);
            deliver(among(rest));
        }
        assertThat(nodes.get(5).role()).isEqualTo(Role.LEADER);
        deliver(among(rest));
        // one heartbeat so every follower hears the final commit index
        for (int i = 0; i < CONFIG.heartbeatTicks(); i++) {
            nodes.get(5).tick();
        }
        collect(5);
        deliver(among(rest));

        for (int id : rest) {
            assertThat(disks.get(id).term(2)).as("node %d index 2", id).isEqualTo(3);
            assertThat(nodes.get(id).commitIndex()).as("node %d commit (leader at %d)", id, nodes.get(5).commitIndex()).isEqualTo(3);
        }
    }
}
