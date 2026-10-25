package dev.seatline.ledger.raft;

import dev.seatline.ledger.sim.Simulation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class ElectionTest {

    @ParameterizedTest
    @ValueSource(longs = {1, 2, 3, 42, 1234})
    void electsExactlyOneLeader(long seed) {
        Simulation sim = new Simulation(seed, 5);
        assertThat(sim.runUntil(() -> sim.leader().isPresent(), 2_000)).isTrue();
        sim.runFor(1_000);

        int leader = sim.leader().orElseThrow();
        long term = sim.node(leader).term();
        for (int id : sim.members()) {
            assertThat(sim.node(id).term()).isEqualTo(term);
            assertThat(sim.node(id).leader()).isEqualTo(leader);
        }
    }

    @Test
    void singleNodeClusterLeadsItself() {
        Simulation sim = new Simulation(7, 1);
        assertThat(sim.runUntil(() -> sim.leader().isPresent(), 1_000)).isTrue();
    }

    @Test
    void reelectsQuicklyAfterLeaderCrash() {
        Simulation sim = new Simulation(11, 5);
        sim.runUntil(() -> sim.leader().isPresent(), 2_000);
        int old = sim.leader().orElseThrow();
        long oldTerm = sim.node(old).term();

        sim.crash(old);
        long crashedAt = sim.now();
        assertThat(sim.runUntil(() -> sim.leader().isPresent(), 2_000)).isTrue();

        int next = sim.leader().orElseThrow();
        assertThat(next).isNotEqualTo(old);
        assertThat(sim.node(next).term()).isGreaterThan(oldTerm);
        // election timeout is 150-300 ms plus a pre-vote and a vote round trip
        assertThat(sim.now() - crashedAt).isLessThan(1_000);
    }

    @Test
    void keepsWorkingWithTwoOfFiveDown() {
        Simulation sim = new Simulation(5, 5);
        sim.runUntil(() -> sim.leader().isPresent(), 2_000);
        int leader = sim.leader().orElseThrow();
        sim.crash(leader);
        sim.crash(leader % 5 + 1);
        assertThat(sim.runUntil(() -> sim.leader().isPresent(), 3_000)).isTrue();
    }

    @Test
    void noLeaderWithoutMajority() {
        Simulation sim = new Simulation(9, 5);
        sim.runUntil(() -> sim.leader().isPresent(), 2_000);
        sim.crash(1);
        sim.crash(2);
        sim.crash(3);
        sim.runFor(1_000); // old leader (if it survived) steps down through check-quorum
        assertThat(sim.runUntil(() -> sim.leader().isPresent(), 5_000)).isFalse();
    }

    @Test
    void leaderCutOffFromMajorityStepsDown() {
        Simulation sim = new Simulation(21, 5);
        sim.runUntil(() -> sim.leader().isPresent(), 2_000);
        int old = sim.leader().orElseThrow();

        sim.isolate(old);
        sim.runFor(1_500);

        assertThat(sim.node(old).role()).isNotEqualTo(Role.LEADER);
        int next = sim.leader().orElseThrow();
        assertThat(next).isNotEqualTo(old);
    }

    @Test
    void rejoiningNodeDoesNotDisruptLeader() {
        Simulation sim = new Simulation(33, 5);
        sim.runUntil(() -> sim.leader().isPresent(), 2_000);
        int leader = sim.leader().orElseThrow();
        long term = sim.node(leader).term();
        int follower = leader % 5 + 1;

        // Without pre-vote the isolated node would keep bumping its term and force an election on return.
        sim.isolate(follower);
        sim.runFor(3_000);
        assertThat(sim.node(follower).term()).isEqualTo(term);
        sim.heal();
        sim.runFor(1_000);

        assertThat(sim.leader()).contains(leader);
        assertThat(sim.node(leader).term()).isEqualTo(term);
    }

    @Test
    void electionsSurviveLossyNetwork() {
        for (long seed = 100; seed < 150; seed++) {
            Simulation sim = new Simulation(seed, 5);
            sim.faults().dropRate = 0.2;
            sim.faults().duplicateRate = 0.1;
            sim.faults().maxDelayMs = 40;
            assertThat(sim.runUntil(() -> sim.leader().isPresent(), 10_000))
                    .as("seed %d elects a leader", seed).isTrue();
        }
    }
}
