package dev.seatline.ledger.seats;

import dev.seatline.ledger.raft.Entry;
import dev.seatline.ledger.raft.MemoryStorage;
import dev.seatline.ledger.raft.RaftConfig;
import dev.seatline.ledger.raft.RaftException;
import dev.seatline.ledger.raft.StateMachine;
import dev.seatline.ledger.sim.Simulation;
import dev.seatline.ledger.sim.Simulation.Proposal;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** One bad request must never take the cluster down. */
class PoisonPillTest {

    private static byte[] request(long seq, SeatCommand c) {
        return SeatCodec.encode(new SeatRequest(9, seq, c));
    }

    @Test
    void leaderTurnsAwayGarbage() {
        Simulation sim = new Simulation(1, 3, RaftConfig.DEFAULT, SeatLedger::new, id -> new MemoryStorage());
        sim.runUntil(() -> sim.leader().isPresent(), 2_000);
        Proposal p = sim.propose(sim.leader().orElseThrow(), new byte[] {7, 7, 7});
        assertThat(p.error()).isInstanceOf(RaftException.BadRequest.class);
    }

    @Test
    void garbageThatReachesTheLogIsHarmless() {
        // Pretend an older leader skipped validation: this wrapper lets anything into the log.
        Simulation sim = new Simulation(2, 5, RaftConfig.DEFAULT.withSnapshotEvery(1_000), () -> new StateMachine() {
            final SeatLedger ledger = new SeatLedger();

            @Override
            public byte[] apply(Entry entry) {
                return ledger.apply(entry);
            }

            @Override
            public byte[] snapshot() {
                return ledger.snapshot();
            }

            @Override
            public void restore(byte[] snapshot) {
                ledger.restore(snapshot);
            }

            @Override
            public boolean accepts(byte[] command) {
                return true;
            }
        }, id -> new MemoryStorage());
        sim.runUntil(() -> sim.leader().isPresent(), 2_000);
        int leader = sim.leader().orElseThrow();

        sim.propose(leader, request(1, new SeatCommand.CreateEvent("ev", List.of("A1", "A2"), 2)));
        Proposal poison = sim.propose(leader, new byte[] {1, 2, 3, 4, 5});
        sim.runFor(300);
        assertThat(SeatCodec.decodeResult(poison.result()).outcome()).isEqualTo(SeatResult.Outcome.BAD_REQUEST);

        // every node replays the bad entry from its log after a full restart, and keeps serving
        for (int id : sim.members()) {
            sim.crash(id);
        }
        for (int id : sim.members()) {
            sim.restart(id);
        }
        sim.runUntil(() -> sim.leader().isPresent(), 3_000);
        Proposal hold = sim.propose(sim.leader().orElseThrow(),
                request(2, new SeatCommand.Hold("ev", List.of("A1"), "fan", "h1", 60_000)));
        sim.runFor(300);
        assertThat(SeatCodec.decodeResult(hold.result()).ok()).isTrue();
        for (int id : sim.members()) {
            assertThat(sim.node(id).lastApplied()).isEqualTo(sim.node(sim.leader().orElseThrow()).lastIndex());
        }
    }
}
