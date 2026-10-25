package dev.seatline.ledger.sim;

import dev.seatline.ledger.raft.CrashableStorage;
import dev.seatline.ledger.raft.FileStorage;
import dev.seatline.ledger.raft.MemoryStorage;
import dev.seatline.ledger.raft.RaftConfig;
import dev.seatline.ledger.sim.Simulation.Proposal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hundreds of seeded runs with a hostile network, random crashes and partitions. The simulator
 * throws the moment a safety rule breaks; at the end we heal everything and check the cluster
 * converges and that every acknowledged write survived.
 */
class RandomizedSafetyTest {

    static final int RUNS = Integer.getInteger("seatline.sim.runs", 300);

    @Test
    void safeUnderCrashesAndPartitions() {
        for (long seed = 1; seed <= RUNS; seed++) {
            runOnce(seed, RaftConfig.DEFAULT, id -> new MemoryStorage());
        }
    }

    @Test
    void safeWithSnapshotsAndRealFiles(@TempDir Path dir) {
        // Same chaos, but every node writes a real WAL and snapshots every 20 entries, so crashes
        // also exercise recovery from disk and slow followers catch up through InstallSnapshot.
        RaftConfig config = RaftConfig.DEFAULT.withSnapshotEvery(20);
        for (long seed = 1; seed <= RUNS / 6; seed++) {
            Path runDir = dir.resolve("run-" + seed);
            runOnce(seed, config, id -> new FileStorage(runDir.resolve("node-" + id), false));
        }
    }

    static void runOnce(long seed, RaftConfig config, IntFunction<CrashableStorage> storages) {
        Random chaos = new Random(seed * 31 + 7);
        Simulation sim = new Simulation(seed, 5, config, ChecksumStateMachine::new, storages);
        sim.faults().dropRate = 0.05 + chaos.nextDouble() * 0.15;
        sim.faults().duplicateRate = chaos.nextDouble() * 0.1;
        sim.faults().maxDelayMs = 5 + chaos.nextInt(60);

        List<Proposal> proposals = new ArrayList<>();
        List<String> commands = new ArrayList<>();
        int counter = 0;

        for (int step = 0; step < 100; step++) {
            int action = chaos.nextInt(10);
            int node = 1 + chaos.nextInt(5);
            switch (action) {
                case 0 -> sim.crash(node);
                case 1, 2 -> sim.restart(node);
                case 3 -> {
                    int[] shuffled = shuffled(chaos);
                    int cut = 1 + chaos.nextInt(4);
                    sim.partition(java.util.Arrays.copyOfRange(shuffled, 0, cut),
                            java.util.Arrays.copyOfRange(shuffled, cut, 5));
                }
                case 4 -> sim.heal();
                default -> {
                    // send a few writes to whoever we think leads (or a random node)
                    int target = sim.leader().orElse(node);
                    for (int i = 0; i < 3; i++) {
                        String c = "w" + counter++;
                        commands.add(c);
                        proposals.add(sim.propose(target, c.getBytes(StandardCharsets.UTF_8)));
                    }
                }
            }
            sim.runFor(chaos.nextInt(300));
        }

        // calm the world down and let it converge
        sim.heal();
        sim.faults().dropRate = 0;
        sim.faults().duplicateRate = 0;
        for (int id : sim.members()) {
            sim.restart(id);
        }
        boolean settled = sim.runUntil(() -> sim.leader().isPresent(), 5_000);
        assertThat(settled).as("seed %d elects a leader after healing", seed).isTrue();
        int leader = sim.leader().orElseThrow();
        sim.propose(leader, "barrier".getBytes(StandardCharsets.UTF_8));
        sim.runFor(2_000);

        long last = sim.node(leader).lastIndex();
        for (int id : sim.members()) {
            assertThat(sim.node(id).lastApplied()).as("seed %d node %d caught up", seed, id).isEqualTo(last);
        }

        // every write we were told succeeded must be in the committed log, exactly once
        Set<String> committed = new HashSet<>();
        sim.committedLog().forEach(e -> {
            if (!e.isNoop()) {
                assertThat(committed.add(new String(e.data(), StandardCharsets.UTF_8)))
                        .as("seed %d: duplicate entry", seed).isTrue();
            }
        });
        for (int i = 0; i < proposals.size(); i++) {
            if (proposals.get(i).succeeded()) {
                assertThat(committed).as("seed %d lost acknowledged write %s", seed, commands.get(i))
                        .contains(commands.get(i));
            }
        }
    }

    private static int[] shuffled(Random r) {
        int[] ids = {1, 2, 3, 4, 5};
        for (int i = ids.length - 1; i > 0; i--) {
            int j = r.nextInt(i + 1);
            int t = ids[i];
            ids[i] = ids[j];
            ids[j] = t;
        }
        return ids;
    }
}
