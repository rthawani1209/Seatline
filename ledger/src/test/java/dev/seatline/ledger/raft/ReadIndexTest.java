package dev.seatline.ledger.raft;

import dev.seatline.ledger.sim.ChecksumStateMachine;
import dev.seatline.ledger.sim.Simulation;
import dev.seatline.ledger.sim.Simulation.Reply;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReadIndexTest {

    private static long appliedCount(Reply r) {
        return ByteBuffer.wrap(r.result()).getLong();
    }

    private static byte[] countQuery(dev.seatline.ledger.raft.StateMachine sm) {
        return ByteBuffer.allocate(8).putLong(((ChecksumStateMachine) sm).applied()).array();
    }

    @Test
    void leaderServesReadsThatSeeEveryCommittedWrite() {
        Simulation sim = new Simulation(3, 5);
        sim.runUntil(() -> sim.leader().isPresent(), 2_000);
        int leader = sim.leader().orElseThrow();
        for (int i = 0; i < 10; i++) {
            sim.propose(leader, ("w" + i).getBytes(StandardCharsets.UTF_8));
        }
        sim.runFor(300);

        List<Reply> replies = new ArrayList<>();
        sim.clientRead(leader, ReadIndexTest::countQuery, replies::add);
        sim.runFor(200);
        assertThat(replies).hasSize(1);
        assertThat(appliedCount(replies.getFirst())).isEqualTo(10);
    }

    @Test
    void followerRedirectsReads() {
        Simulation sim = new Simulation(4, 5);
        sim.runUntil(() -> sim.leader().isPresent(), 2_000);
        int follower = sim.leader().orElseThrow() % 5 + 1;
        List<Reply> replies = new ArrayList<>();
        sim.clientRead(follower, ReadIndexTest::countQuery, replies::add);
        sim.runFor(100);
        assertThat(replies.getFirst().error()).isInstanceOf(RaftException.NotLeader.class);
    }

    @Test
    void deposedLeaderNeverServesAStaleRead() {
        // Without check-quorum a cut-off leader keeps believing it leads, which is exactly the
        // situation ReadIndex has to survive.
        Simulation sim = new Simulation(5, 5, RaftConfig.DEFAULT.withCheckQuorum(false));
        sim.runUntil(() -> sim.leader().isPresent(), 2_000);
        int old = sim.leader().orElseThrow();
        sim.propose(old, "before split".getBytes(StandardCharsets.UTF_8));
        sim.runFor(200);

        sim.isolate(old);
        sim.runUntil(() -> java.util.Arrays.stream(sim.members())
                .anyMatch(id -> id != old && sim.node(id).role() == Role.LEADER), 2_000);
        int fresh = java.util.Arrays.stream(sim.members())
                .filter(id -> id != old && sim.node(id).role() == Role.LEADER).findFirst().orElseThrow();
        Simulation.Proposal write = sim.propose(fresh, "after split".getBytes(StandardCharsets.UTF_8));
        sim.runFor(100);
        assertThat(write.succeeded()).isTrue();
        assertThat(sim.node(old).role()).isEqualTo(Role.LEADER);

        // A read sent to the old leader must not answer with state that misses that write.
        List<Reply> replies = new ArrayList<>();
        sim.clientRead(old, ReadIndexTest::countQuery, replies::add);
        sim.runFor(1_000);
        assertThat(replies).allSatisfy(r -> assertThat(r.error()).isNotNull());
    }

    @Test
    void readsWaitForNewLeadersFirstCommit() {
        Simulation sim = new Simulation(6, 5);
        sim.runUntil(() -> sim.leader().isPresent(), 2_000);
        int leader = sim.leader().orElseThrow();
        sim.propose(leader, "x".getBytes(StandardCharsets.UTF_8));
        sim.runFor(200);
        sim.crash(leader);
        sim.runUntil(() -> sim.leader().isPresent(), 2_000);
        int next = sim.leader().orElseThrow();

        List<Reply> replies = new ArrayList<>();
        sim.clientRead(next, ReadIndexTest::countQuery, replies::add);
        sim.runFor(300);
        assertThat(replies).hasSize(1);
        assertThat(appliedCount(replies.getFirst())).isEqualTo(1);
    }
}
