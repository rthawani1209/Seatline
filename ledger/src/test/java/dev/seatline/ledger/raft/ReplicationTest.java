package dev.seatline.ledger.raft;

import dev.seatline.ledger.sim.Simulation;
import dev.seatline.ledger.sim.Simulation.Proposal;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReplicationTest {

    private static byte[] cmd(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static Simulation stableCluster(long seed) {
        Simulation sim = new Simulation(seed, 5);
        sim.runUntil(() -> sim.leader().isPresent(), 2_000);
        return sim;
    }

    @Test
    void committedEntriesReachEveryNode() {
        Simulation sim = stableCluster(1);
        int leader = sim.leader().orElseThrow();
        List<Proposal> proposals = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            proposals.add(sim.propose(leader, cmd("seat-" + i)));
        }
        sim.runFor(500);

        assertThat(proposals).allMatch(Proposal::succeeded);
        assertThat(new String(proposals.get(7).result(), StandardCharsets.UTF_8)).isEqualTo("seat-7");
        long last = sim.node(leader).lastIndex();
        for (int id : sim.members()) {
            assertThat(sim.node(id).commitIndex()).isEqualTo(last);
            assertThat(sim.node(id).lastApplied()).isEqualTo(last);
        }
    }

    @Test
    void followerRejectsProposals() {
        Simulation sim = stableCluster(2);
        int follower = sim.leader().orElseThrow() % 5 + 1;
        Proposal p = sim.propose(follower, cmd("x"));
        assertThat(p.done()).isTrue();
        assertThat(p.error()).isInstanceOf(RaftException.NotLeader.class);
        assertThat(((RaftException.NotLeader) p.error()).leaderHint()).isEqualTo(sim.leader().orElseThrow());
    }

    @Test
    void minorityCannotCommit() {
        Simulation sim = stableCluster(3);
        int leader = sim.leader().orElseThrow();
        int buddy = leader % 5 + 1;
        // leader plus one follower on one side, the other three on the other
        int[] minority = {leader, buddy};
        int[] majority = java.util.Arrays.stream(sim.members()).filter(id -> id != leader && id != buddy).toArray();
        sim.partition(minority, majority);

        Proposal stranded = sim.propose(leader, cmd("lost?"));
        sim.runFor(2_000);

        // the old leader steps down; its entry never committed, so the caller hears "unknown"
        assertThat(stranded.done()).isTrue();
        assertThat(stranded.error()).isInstanceOf(RaftException.OutcomeUnknown.class);

        int newLeader = sim.leader().orElseThrow();
        assertThat(majority).contains(newLeader);
        Proposal ok = sim.propose(newLeader, cmd("majority side"));
        sim.runFor(200);
        assertThat(ok.succeeded()).isTrue();

        sim.heal();
        sim.runFor(1_000);
        // the stranded entry was overwritten everywhere; every node agrees on the majority's log
        long last = sim.node(newLeader).lastIndex();
        for (int id : sim.members()) {
            assertThat(sim.node(id).lastApplied()).isEqualTo(last);
        }
        assertThat(sim.committedLog()).noneMatch(e -> new String(e.data(), StandardCharsets.UTF_8).equals("lost?"));
    }

    @Test
    void restartedNodeCatchesUp() {
        Simulation sim = stableCluster(4);
        int leader = sim.leader().orElseThrow();
        int victim = leader % 5 + 1;
        sim.crash(victim);
        for (int i = 0; i < 500; i++) {
            sim.propose(leader, cmd("while-down-" + i));
            if (i % 50 == 0) {
                sim.runFor(20);
            }
        }
        sim.runFor(500);
        sim.restart(victim);
        sim.runFor(1_000);
        assertThat(sim.node(victim).lastApplied()).isEqualTo(sim.node(leader).lastIndex());
    }

    @Test
    void divergentFollowerLogsAreRepaired() {
        Simulation sim = stableCluster(5);
        // Build up uncommitted junk on a series of short-lived leaders that keep getting cut off,
        // then make sure the final leader overwrites all of it.
        for (int round = 0; round < 4; round++) {
            int leader = sim.leader().orElseThrow();
            sim.isolate(leader);
            for (int i = 0; i < 30; i++) {
                sim.propose(leader, cmd("junk-" + round + "-" + i));
            }
            sim.runFor(1_000);
            sim.heal();
            sim.runUntil(() -> sim.leader().isPresent(), 2_000);
            int good = sim.leader().orElseThrow();
            for (int i = 0; i < 10; i++) {
                sim.propose(good, cmd("good-" + round + "-" + i));
            }
            sim.runFor(300);
        }
        sim.runFor(1_000);
        int leader = sim.leader().orElseThrow();
        for (int id : sim.members()) {
            assertThat(sim.node(id).lastApplied()).isEqualTo(sim.node(leader).lastIndex());
        }
        assertThat(sim.committedLog().stream().map(e -> new String(e.data(), StandardCharsets.UTF_8)))
                .noneMatch(s -> s.startsWith("junk"))
                .filteredOn(s -> s.startsWith("good")).hasSize(40);
    }
}
