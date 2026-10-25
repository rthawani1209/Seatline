package dev.seatline.ledger.raft;

import dev.seatline.ledger.sim.ChecksumStateMachine;
import dev.seatline.ledger.sim.Simulation;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class SnapshotTest {

    private static byte[] cmd(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void laggingFollowerCatchesUpFromSnapshot() {
        Simulation sim = new Simulation(8, 5, RaftConfig.DEFAULT.withSnapshotEvery(50));
        sim.runUntil(() -> sim.leader().isPresent(), 2_000);
        int leader = sim.leader().orElseThrow();
        int victim = leader % 5 + 1;
        sim.crash(victim);

        for (int i = 0; i < 400; i++) {
            sim.propose(leader, cmd("sale-" + i));
            if (i % 20 == 0) {
                sim.runFor(30);
            }
        }
        sim.runFor(500);
        // the leader no longer has the early entries the victim is missing
        assertThat(sim.node(leader).commitIndex()).isGreaterThan(300);

        sim.restart(victim);
        sim.runFor(1_000);

        ChecksumStateMachine a = (ChecksumStateMachine) sim.stateMachine(leader);
        ChecksumStateMachine b = (ChecksumStateMachine) sim.stateMachine(victim);
        assertThat(sim.node(victim).lastApplied()).isEqualTo(sim.node(leader).lastApplied());
        assertThat(b.hash()).isEqualTo(a.hash());
        assertThat(b.applied()).isEqualTo(400);
    }

    @Test
    void restartRestoresFromOwnSnapshot() {
        Simulation sim = new Simulation(9, 3, RaftConfig.DEFAULT.withSnapshotEvery(25));
        sim.runUntil(() -> sim.leader().isPresent(), 2_000);
        int leader = sim.leader().orElseThrow();
        for (int i = 0; i < 120; i++) {
            sim.propose(leader, cmd("x" + i));
        }
        sim.runFor(500);
        long hashBefore = ((ChecksumStateMachine) sim.stateMachine(1)).hash();

        for (int id : sim.members()) {
            sim.crash(id);
        }
        for (int id : sim.members()) {
            sim.restart(id);
        }
        sim.runUntil(() -> sim.leader().isPresent(), 3_000);
        sim.runFor(500);
        for (int id : sim.members()) {
            assertThat(((ChecksumStateMachine) sim.stateMachine(id)).hash()).isEqualTo(hashBefore);
        }
    }
}
