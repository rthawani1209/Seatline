package dev.seatline.ledger.sim;

import dev.seatline.ledger.raft.Completion;
import dev.seatline.ledger.raft.Entry;
import dev.seatline.ledger.raft.MemoryStorage;
import dev.seatline.ledger.raft.Message;
import dev.seatline.ledger.raft.RaftConfig;
import dev.seatline.ledger.raft.RaftException;
import dev.seatline.ledger.raft.RaftNode;
import dev.seatline.ledger.raft.RaftObserver;
import dev.seatline.ledger.raft.Role;
import dev.seatline.ledger.raft.StateMachine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.stream.IntStream;

/**
 * A deterministic, single-threaded world for a Raft group: virtual time, a network that drops,
 * delays, duplicates and reorders messages, partitions, and crashes. Everything random comes from
 * one seed, so any failure can be replayed exactly by rerunning that seed.
 *
 * <p>The simulation also checks Raft's safety properties continuously and throws as soon as one
 * breaks: at most one leader per term, and no two nodes ever apply different entries at the same
 * log index.
 */
public final class Simulation {

    public static final int TICK_MS = 10;

    private final long seed;
    private final Random random;
    private final RaftConfig config;
    private final int[] members;
    private final Supplier<StateMachine> stateMachines;
    private final Map<Integer, SimNode> nodes = new HashMap<>();
    private final PriorityQueue<Event> events = new PriorityQueue<>();
    private final SafetyChecker safety = new SafetyChecker();

    private final NetworkFaults faults = new NetworkFaults();
    private final int[][] linkGroup; // linkGroup[a][b] != 0 means a and b can't talk
    private long now;
    private long eventSeq;
    private long delivered;
    private long dropped;

    /** Knobs for the unreliable network. Defaults are a healthy LAN. */
    public static final class NetworkFaults {
        public double dropRate = 0.0;
        public double duplicateRate = 0.0;
        public int minDelayMs = 1;
        public int maxDelayMs = 5;
    }

    private record Event(long time, long seq, Runnable action) implements Comparable<Event> {
        @Override
        public int compareTo(Event o) {
            return time != o.time ? Long.compare(time, o.time) : Long.compare(seq, o.seq);
        }
    }

    final class SimNode {
        final int id;
        final MemoryStorage storage = new MemoryStorage();
        RaftNode raft;
        StateMachine stateMachine;
        boolean up;
        int incarnation;

        SimNode(int id) {
            this.id = id;
        }
    }

    public Simulation(long seed, int size, RaftConfig config, Supplier<StateMachine> stateMachines) {
        this.seed = seed;
        this.random = new Random(seed);
        this.config = config;
        this.members = IntStream.rangeClosed(1, size).toArray();
        this.stateMachines = stateMachines;
        this.linkGroup = new int[size + 1][size + 1];
        for (int id : members) {
            SimNode n = new SimNode(id);
            nodes.put(id, n);
            start(n);
        }
    }

    public Simulation(long seed, int size) {
        this(seed, size, RaftConfig.DEFAULT, () -> entry -> entry.data());
    }

    // ---------------------------------------------------------------- running

    /** Runs the world forward by {@code ms} of virtual time. */
    public void runFor(long ms) {
        long until = now + ms;
        while (!events.isEmpty() && events.peek().time() <= until) {
            Event e = events.poll();
            now = e.time();
            e.action().run();
        }
        now = until;
    }

    /** Runs until the condition holds (checked every tick) or the time limit passes. */
    public boolean runUntil(BooleanSupplier condition, long maxMs) {
        long deadline = now + maxMs;
        while (now < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            runFor(TICK_MS);
        }
        return condition.getAsBoolean();
    }

    private void schedule(long delayMs, Runnable action) {
        events.add(new Event(now + delayMs, eventSeq++, action));
    }

    // ---------------------------------------------------------------- nodes

    private void start(SimNode n) {
        n.stateMachine = stateMachines.get();
        n.raft = new RaftNode(n.id, members, config, n.storage, n.stateMachine,
                new Random(random.nextLong()), () -> now, safety);
        n.up = true;
        n.incarnation++;
        int incarnation = n.incarnation;
        // stagger ticks so nodes don't move in lockstep
        scheduleTick(n, incarnation, 1 + random.nextInt(TICK_MS));
    }

    private void scheduleTick(SimNode n, int incarnation, long delay) {
        schedule(delay, () -> {
            if (!n.up || n.incarnation != incarnation) {
                return;
            }
            n.raft.tick();
            flush(n);
            scheduleTick(n, incarnation, TICK_MS);
        });
    }

    /** Kills a node. Unsynced writes are lost; in-flight messages to it are dropped on arrival. */
    public void crash(int id) {
        SimNode n = nodes.get(id);
        if (!n.up) {
            return;
        }
        n.up = false;
        n.raft = null;
        n.stateMachine = null;
        n.storage.crash();
    }

    /** Brings a crashed node back from whatever its storage kept. */
    public void restart(int id) {
        SimNode n = nodes.get(id);
        if (n.up) {
            return;
        }
        start(n);
    }

    public boolean isUp(int id) {
        return nodes.get(id).up;
    }

    public RaftNode node(int id) {
        SimNode n = nodes.get(id);
        return n.up ? n.raft : null;
    }

    public StateMachine stateMachine(int id) {
        return nodes.get(id).stateMachine;
    }

    public int[] members() {
        return members.clone();
    }

    // ---------------------------------------------------------------- network

    public NetworkFaults faults() {
        return faults;
    }

    /** Splits the cluster: nodes can only talk to nodes in the same group. Unlisted nodes are isolated. */
    public void partition(int[]... groups) {
        int[] groupOf = new int[members.length + 1];
        for (int g = 0; g < groups.length; g++) {
            for (int id : groups[g]) {
                groupOf[id] = g + 1;
            }
        }
        for (int a : members) {
            for (int b : members) {
                boolean same = groupOf[a] != 0 && groupOf[a] == groupOf[b];
                linkGroup[a][b] = same || a == b ? 0 : 1;
            }
        }
    }

    /** Cuts one node off from everyone. */
    public void isolate(int id) {
        for (int other : members) {
            if (other != id) {
                linkGroup[id][other] = 1;
                linkGroup[other][id] = 1;
            }
        }
    }

    public void heal() {
        for (int[] row : linkGroup) {
            Arrays.fill(row, 0);
        }
    }

    public boolean connected(int a, int b) {
        return linkGroup[a][b] == 0;
    }

    private void flush(SimNode n) {
        for (Message m : n.raft.drainMessages()) {
            route(m);
        }
    }

    private void route(Message m) {
        if (!connected(m.from(), m.to()) || random.nextDouble() < faults.dropRate) {
            dropped++;
            return;
        }
        int copies = random.nextDouble() < faults.duplicateRate ? 2 : 1;
        for (int c = 0; c < copies; c++) {
            int span = faults.maxDelayMs - faults.minDelayMs;
            long delay = faults.minDelayMs + (span > 0 ? random.nextInt(span + 1) : 0);
            schedule(delay, () -> deliver(m));
        }
    }

    private void deliver(Message m) {
        SimNode target = nodes.get(m.to());
        // the link may have been cut while the message was in flight
        if (!target.up || !connected(m.from(), m.to())) {
            dropped++;
            return;
        }
        delivered++;
        target.raft.step(m);
        flush(target);
    }

    // ---------------------------------------------------------------- clients

    /** The single node that is up and leading in the highest term, if any. */
    public Optional<Integer> leader() {
        int best = -1;
        long bestTerm = -1;
        for (SimNode n : nodes.values()) {
            if (n.up && n.raft.role() == Role.LEADER && n.raft.term() > bestTerm) {
                best = n.id;
                bestTerm = n.raft.term();
            }
        }
        return best < 0 ? Optional.empty() : Optional.of(best);
    }

    /** Result holder for a proposal made through the simulator. */
    public static final class Proposal {
        private byte[] result;
        private RaftException error;
        private boolean done;

        public boolean done() {
            return done;
        }

        public boolean succeeded() {
            return done && error == null;
        }

        public byte[] result() {
            return result;
        }

        public RaftException error() {
            return error;
        }
    }

    /** Proposes a command on the given node. */
    public Proposal propose(int nodeId, byte[] command) {
        Proposal p = new Proposal();
        SimNode n = nodes.get(nodeId);
        if (!n.up) {
            p.done = true;
            p.error = new RaftException("node " + nodeId + " is down");
            return p;
        }
        n.raft.propose(command, new Completion<>() {
            @Override
            public void complete(byte[] result) {
                p.result = result;
                p.done = true;
            }

            @Override
            public void fail(RaftException error) {
                p.error = error;
                p.done = true;
            }
        });
        flush(n);
        return p;
    }

    // ---------------------------------------------------------------- inspection

    public long now() {
        return now;
    }

    public long seed() {
        return seed;
    }

    public long messagesDelivered() {
        return delivered;
    }

    public long messagesDropped() {
        return dropped;
    }

    /** Entries applied anywhere in the cluster, in log order (what every node must agree on). */
    public List<Entry> committedLog() {
        return new ArrayList<>(safety.applied.values());
    }

    public Map<Long, Integer> leadersByTerm() {
        return Map.copyOf(safety.leaders);
    }

    /** Watches every node for violations of Raft's safety guarantees. */
    private final class SafetyChecker implements RaftObserver {
        final Map<Long, Integer> leaders = new HashMap<>();
        final java.util.TreeMap<Long, Entry> applied = new java.util.TreeMap<>();

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
                        + entry.index() + " (term " + entry.term() + ", others applied term " + first.term() + ")");
            }
        }
    }

    public static final class SafetyViolation extends AssertionError {
        public SafetyViolation(long seed, String message) {
            super(message + " [replay with seed " + seed + "]");
        }
    }
}
