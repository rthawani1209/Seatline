package dev.seatline.ledger.sim;

import dev.seatline.ledger.check.LinearizabilityChecker;
import dev.seatline.ledger.check.Operation;
import dev.seatline.ledger.check.SeatModel;
import dev.seatline.ledger.raft.MemoryStorage;
import dev.seatline.ledger.raft.RaftConfig;
import dev.seatline.ledger.raft.RaftException;
import dev.seatline.ledger.seats.SeatCodec;
import dev.seatline.ledger.seats.SeatCommand;
import dev.seatline.ledger.seats.SeatLedger;
import dev.seatline.ledger.seats.SeatRequest;
import dev.seatline.ledger.seats.SeatResult;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * The chaos run: fans buying seats from a 5-node ledger while servers crash and the network drops,
 * delays, duplicates and splits messages. Every client operation is recorded with when it started
 * and finished, and afterwards we check:
 *
 * <ol>
 *   <li>the history is linearizable against the seat ledger's specification,
 *   <li>no seat was ever sold to two fans (no ticket id clash across the run),
 *   <li>all five copies of the ledger end up byte-for-byte identical and internally consistent.
 * </ol>
 *
 * Few seats and many fans on purpose: that's where clashes happen.
 */
public final class SeatChaos {

    public static final String EVENT = "spring-static";
    private static final long NO_EXPIRY = 1_000_000_000_000L;

    public record Options(int clients, int opsPerClient, int seats, RaftConfig config) {
        public static final Options DEFAULT = new Options(5, 25, 6, RaftConfig.DEFAULT.withSnapshotEvery(40));
    }

    public record Report(long seed, int operations, int unknownOutcomes, int ticketsSold, int leaderTerms,
                         LinearizabilityChecker.Result linearizability, long virtualMillis) {}

    public static Report run(long seed) {
        return run(seed, Options.DEFAULT);
    }

    public static Report run(long seed, Options opts) {
        Random rnd = new Random(seed ^ 0x5EA7L);
        // Every other run turns check-quorum off, so a cut-off leader keeps thinking it leads for a
        // long time. That's the window where stale reads would happen if ReadIndex were wrong.
        RaftConfig config = opts.config().withCheckQuorum(seed % 2 == 0);
        Simulation sim = new Simulation(seed, 5, config, SeatLedger::new, id -> new MemoryStorage());
        List<String> seatIds = new ArrayList<>();
        for (int i = 1; i <= opts.seats(); i++) {
            seatIds.add("A" + i);
        }
        SeatCommand create = new SeatCommand.CreateEvent(EVENT, seatIds, 4);

        // set up the event on a calm cluster
        if (!sim.runUntil(() -> sim.leader().isPresent(), 5_000)) {
            throw new IllegalStateException("no leader at start, seed " + seed);
        }
        Simulation.Proposal p = sim.propose(sim.leader().orElseThrow(), SeatCodec.encode(new SeatRequest(0, 0, create)));
        sim.runUntil(p::done, 2_000);
        if (!p.succeeded()) {
            throw new IllegalStateException("couldn't create event, seed " + seed);
        }

        // now make it hostile
        sim.faults().dropRate = 0.02 + rnd.nextDouble() * 0.1;
        sim.faults().duplicateRate = rnd.nextDouble() * 0.05;
        sim.faults().maxDelayMs = 5 + rnd.nextInt(40);

        List<Operation> history = new ArrayList<>();
        List<Client> clients = new ArrayList<>();
        for (int c = 1; c <= opts.clients(); c++) {
            Client client = new Client(c, sim, new Random(rnd.nextLong()), seatIds, opts.opsPerClient(), history);
            clients.add(client);
            sim.after(rnd.nextInt(50), client::next);
        }
        boolean[] calm = {false};
        scheduleNemesis(sim, rnd, calm);

        sim.runUntil(() -> clients.stream().allMatch(Client::finished), 120_000);
        calm[0] = true;

        // heal everything and let the cluster settle before comparing replicas
        sim.heal();
        sim.faults().dropRate = 0;
        sim.faults().duplicateRate = 0;
        for (int id : sim.members()) {
            sim.restart(id);
        }
        sim.runUntil(() -> sim.leader().isPresent(), 5_000);
        Simulation.Proposal barrier = sim.propose(sim.leader().orElseThrow(),
                SeatCodec.encode(new SeatRequest(0, 0, new SeatCommand.ExpireDue())));
        sim.runUntil(barrier::done, 3_000);
        sim.runFor(1_000);

        if (!clients.stream().allMatch(Client::finished)) {
            throw new AssertionError("clients never finished [seed " + seed + "]");
        }
        checkReplicasAgree(sim, seed);
        checkNoDoubleSale(history, seed);

        LinearizabilityChecker.Result lin = LinearizabilityChecker.check(new SeatModel(List.of(create)), history);
        if (lin == LinearizabilityChecker.Result.NOT_LINEARIZABLE) {
            throw new AssertionError("history is not linearizable [seed " + seed + "]");
        }
        int unknown = (int) history.stream().filter(Operation::outcomeUnknown).count();
        int sold = (int) ((SeatLedger) sim.stateMachine(1)).seatMap(EVENT, 0).stream()
                .filter(v -> v.status() == SeatLedger.SeatStatus.SOLD).count();
        return new Report(seed, history.size(), unknown, sold, sim.leadersByTerm().size(), lin, sim.now());
    }

    /** Breaks something every few hundred milliseconds until {@code calm[0]} is set. */
    private static void scheduleNemesis(Simulation sim, Random rnd, boolean[] calm) {
        sim.after(100 + rnd.nextInt(400), () -> {
            if (calm[0]) {
                return;
            }
            int node = 1 + rnd.nextInt(5);
            int downNow = (int) Arrays.stream(sim.members()).filter(id -> !sim.isUp(id)).count();
            switch (rnd.nextInt(6)) {
                case 0 -> {
                    // kill the leader half the time, that's the interesting case
                    int target = rnd.nextBoolean() ? sim.leader().orElse(node) : node;
                    if (downNow < 2) {
                        sim.crash(target);
                    }
                }
                case 1, 2 -> {
                    for (int id : sim.members()) {
                        sim.restart(id);
                    }
                }
                case 3 -> {
                    int[] ids = sim.members();
                    for (int i = ids.length - 1; i > 0; i--) {
                        int j = rnd.nextInt(i + 1);
                        int t = ids[i];
                        ids[i] = ids[j];
                        ids[j] = t;
                    }
                    int cut = 1 + rnd.nextInt(2);
                    sim.partition(Arrays.copyOfRange(ids, 0, cut), Arrays.copyOfRange(ids, cut, ids.length));
                }
                case 4 -> sim.isolate(sim.leader().orElse(node));
                default -> sim.heal();
            }
            scheduleNemesis(sim, rnd, calm);
        });
    }

    private static void checkReplicasAgree(Simulation sim, long seed) {
        byte[] reference = null;
        for (int id : sim.members()) {
            SeatLedger ledger = (SeatLedger) sim.stateMachine(id);
            ledger.checkInvariants();
            byte[] snap = ledger.snapshot();
            if (reference == null) {
                reference = snap;
            } else if (!Arrays.equals(reference, snap)) {
                throw new AssertionError("node " + id + " ended with a different ledger [seed " + seed + "]");
            }
        }
    }

    /** Every ticket any client was told about names a different seat. */
    private static void checkNoDoubleSale(List<Operation> history, long seed) {
        Set<String> seatsSold = new HashSet<>();
        for (Operation op : history) {
            if (op.input() instanceof SeatModel.Write w && w.command() instanceof SeatCommand.Confirm
                    && op.output() instanceof String out && out.startsWith("OK ")) {
                for (String ticket : out.substring(3).split(",")) {
                    String seat = ticket.substring(ticket.lastIndexOf('-') + 1);
                    if (!seatsSold.add(seat)) {
                        throw new AssertionError("seat " + seat + " was sold twice [seed " + seed + "]");
                    }
                }
            }
        }
    }

    /**
     * A fan's app. One request at a time; on timeout or "not the leader" it retries the same request
     * (same sequence number) on another server, so a retry can never apply twice.
     */
    private static final class Client {
        static final long TIMEOUT_MS = 300;
        static final long GIVE_UP_MS = 5_000;

        final int id;
        final Simulation sim;
        final Random rnd;
        final List<String> seatIds;
        final List<Operation> history;
        int remaining;
        long seq;
        int holdCounter;
        int leaderGuess = 1;
        final List<String> myHolds = new ArrayList<>();

        // the operation in flight
        Object input;
        byte[] request;
        long invokedAt;
        int attempt;
        boolean done;

        Client(int id, Simulation sim, Random rnd, List<String> seatIds, int ops, List<Operation> history) {
            this.id = id;
            this.sim = sim;
            this.rnd = rnd;
            this.seatIds = seatIds;
            this.remaining = ops;
            this.history = history;
        }

        boolean finished() {
            return remaining == 0 && input == null;
        }

        void next() {
            if (remaining == 0) {
                return;
            }
            remaining--;
            input = pickOperation();
            if (input instanceof SeatModel.Write w) {
                seq++;
                request = SeatCodec.encode(new SeatRequest(1000 + id, seq, w.command()));
            }
            invokedAt = sim.now();
            send();
        }

        Object pickOperation() {
            int roll = rnd.nextInt(10);
            if (roll < 4 || myHolds.isEmpty() && roll < 8) {
                List<String> shuffled = new ArrayList<>(seatIds);
                java.util.Collections.shuffle(shuffled, rnd);
                List<String> want = List.copyOf(shuffled.subList(0, 1 + rnd.nextInt(2)));
                String holdId = "c" + id + "-" + (++holdCounter);
                return new SeatModel.Write(new SeatCommand.Hold(EVENT, want, "fan" + id, holdId, NO_EXPIRY));
            }
            if (roll < 8 && !myHolds.isEmpty()) {
                String holdId = myHolds.remove(rnd.nextInt(myHolds.size()));
                return roll < 7
                        ? new SeatModel.Write(new SeatCommand.Confirm(holdId, "pay-" + holdId))
                        : new SeatModel.Write(new SeatCommand.Release(holdId));
            }
            return new SeatModel.Read(EVENT);
        }

        void send() {
            int myAttempt = ++attempt;
            int target = leaderGuess;
            if (input instanceof SeatModel.Write) {
                sim.clientWrite(target, request, reply -> onReply(myAttempt, reply));
            } else {
                sim.clientRead(target, sm -> SeatModel.summarize(((SeatLedger) sm).seatMap(EVENT, 0))
                        .getBytes(StandardCharsets.UTF_8), reply -> onReply(myAttempt, reply));
            }
            sim.after(TIMEOUT_MS, () -> {
                if (attempt == myAttempt && input != null) {
                    leaderGuess = leaderGuess % 5 + 1;
                    retry();
                }
            });
        }

        void onReply(int forAttempt, Simulation.Reply reply) {
            if (forAttempt != attempt || input == null) {
                return; // answer to an attempt we already gave up on
            }
            if (reply.error() != null) {
                if (reply.error() instanceof RaftException.NotLeader nl && nl.leaderHint() > 0) {
                    leaderGuess = nl.leaderHint();
                } else {
                    leaderGuess = leaderGuess % 5 + 1;
                }
                retry();
                return;
            }
            String output;
            if (input instanceof SeatModel.Write w) {
                SeatResult r = SeatCodec.decodeResult(reply.result());
                output = SeatModel.summarize(r);
                if (r.ok() && w.command() instanceof SeatCommand.Hold h) {
                    myHolds.add(h.holdId());
                }
            } else {
                output = new String(reply.result(), StandardCharsets.UTF_8);
            }
            history.add(new Operation(id, input, output, invokedAt, sim.now()));
            finishOp();
        }

        void retry() {
            if (sim.now() - invokedAt > GIVE_UP_MS) {
                // We never found out. A write may or may not have happened; a read changed nothing.
                if (input instanceof SeatModel.Write) {
                    history.add(Operation.unknown(id, input, invokedAt));
                }
                finishOp();
                return;
            }
            sim.after(10 + rnd.nextInt(30), () -> {
                if (input != null) {
                    send();
                }
            });
        }

        void finishOp() {
            input = null;
            attempt++;
            sim.after(rnd.nextInt(40), this::next);
        }
    }
}
