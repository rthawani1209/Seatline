package dev.seatline.ledger.raft;

import dev.seatline.ledger.raft.Message.AppendEntries;
import dev.seatline.ledger.raft.Message.AppendResponse;
import dev.seatline.ledger.raft.Message.InstallSnapshot;
import dev.seatline.ledger.raft.Message.RequestVote;
import dev.seatline.ledger.raft.Message.SnapshotResponse;
import dev.seatline.ledger.raft.Message.VoteResponse;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.LongSupplier;

/**
 * One member of a Raft group, written as a plain single-threaded state machine.
 *
 * <p>Nothing in here touches threads, sockets or the system clock. The caller drives it with
 * {@link #tick()}, {@link #step(Message)} and {@link #propose}, then collects outgoing messages
 * with {@link #drainMessages()}. That keeps the algorithm easy to read and lets the simulator run
 * thousands of seeded, fully repeatable executions. Callers must not use one node from two threads
 * at once.
 *
 * <p>Section numbers in comments refer to the Raft paper (Ongaro and Ousterhout, 2014).
 */
public final class RaftNode {

    private final int id;
    private final int[] peers;
    private final RaftConfig config;
    private final Storage storage;
    private final StateMachine stateMachine;
    private final Random random;
    private final LongSupplier clock;
    private final RaftObserver observer;

    private final List<Message> outbox = new ArrayList<>();

    private long term;
    private int votedFor;
    private Role role = Role.FOLLOWER;
    private int leader = HardState.NONE;

    private long commitIndex;
    private long lastApplied;

    private int electionElapsed;
    private int heartbeatElapsed;
    private int randomizedElectionTimeout;

    private final Set<Integer> votesFor = new HashSet<>();
    private final Set<Integer> votesAgainst = new HashSet<>();

    // leader only
    private final Map<Integer, Progress> progress = new HashMap<>();
    private final TreeMap<Long, Pending> pending = new TreeMap<>();

    private static final class Progress {
        long next;
        long match;
        boolean inflight;
        boolean recentlyActive;
        long ackedReadSeq;

        Progress(long next) {
            this.next = next;
        }
    }

    private record Pending(long term, Completion<byte[]> completion) {}

    public RaftNode(int id, int[] members, RaftConfig config, Storage storage, StateMachine stateMachine,
                    Random random, LongSupplier clock, RaftObserver observer) {
        this.id = id;
        this.peers = Arrays.stream(members).filter(m -> m != id).toArray();
        if (peers.length != members.length - 1) {
            throw new IllegalArgumentException("node " + id + " must be listed in members exactly once");
        }
        this.config = config;
        this.storage = storage;
        this.stateMachine = stateMachine;
        this.random = random;
        this.clock = clock;
        this.observer = observer;

        HardState hs = storage.hardState();
        this.term = hs.term();
        this.votedFor = hs.votedFor();

        // Everything in the snapshot was committed, so start from there instead of replaying it.
        Snapshot snap = storage.snapshot();
        if (snap.lastIndex() > 0) {
            stateMachine.restore(snap.data());
            commitIndex = snap.lastIndex();
            lastApplied = snap.lastIndex();
        }
        resetElectionTimer();
    }

    // ---------------------------------------------------------------- inputs

    /** Advances logical time by one tick. */
    public void tick() {
        electionElapsed++;
        if (role == Role.LEADER) {
            // Check quorum: a leader that hasn't heard from a majority for a whole election timeout
            // steps down, so a leader stranded on the minority side of a split stops claiming the role.
            if (electionElapsed >= config.electionTicks()) {
                electionElapsed = 0;
                int active = 1;
                for (Progress p : progress.values()) {
                    if (p.recentlyActive) {
                        active++;
                    }
                    p.recentlyActive = false;
                }
                if (active < quorum()) {
                    becomeFollower(term, HardState.NONE);
                    return;
                }
            }
            heartbeatElapsed++;
            if (heartbeatElapsed >= config.heartbeatTicks()) {
                heartbeatElapsed = 0;
                for (int peer : peers) {
                    sendAppend(peer, true);
                }
            }
        } else if (electionElapsed >= randomizedElectionTimeout) {
            startPreVote();
        }
    }

    /**
     * Handles a message from a peer. Responses are queued in the outbox like any other message.
     */
    public void step(Message m) {
        if (m.to() != id) {
            throw new IllegalArgumentException("message for node " + m.to() + " delivered to " + id);
        }

        if (m.term() > term) {
            boolean preVoteTraffic = (m instanceof RequestVote rv && rv.preVote())
                    || (m instanceof VoteResponse vr && vr.preVote() && vr.granted());
            if (!preVoteTraffic) {
                // §5.1: a higher term always wins. Remember who is leading if the message says so.
                boolean fromLeader = m instanceof AppendEntries || m instanceof InstallSnapshot;
                becomeFollower(m.term(), fromLeader ? m.from() : HardState.NONE);
            }
        } else if (m.term() < term) {
            // Stale sender. Reply so it learns the new term and steps down; ignore stale responses.
            switch (m) {
                case AppendEntries ae -> send(new AppendResponse(id, ae.from(), term, false, 0, ae.prevLogIndex(), -1, 0, 0));
                case RequestVote rv -> send(new VoteResponse(id, rv.from(), term, false, rv.preVote()));
                case InstallSnapshot is -> send(new SnapshotResponse(id, is.from(), term, 0, 0));
                default -> { }
            }
            return;
        }

        switch (m) {
            case RequestVote rv -> handleRequestVote(rv);
            case VoteResponse vr -> handleVoteResponse(vr);
            case AppendEntries ae -> handleAppendEntries(ae);
            case AppendResponse ar -> handleAppendResponse(ar);
            case InstallSnapshot is -> handleInstallSnapshot(is);
            case SnapshotResponse sr -> handleSnapshotResponse(sr);
        }
    }

    /**
     * Proposes a command. The completion fires once the entry is committed and applied here, with
     * the state machine's result. Returns the log index, or -1 if this node isn't the leader.
     */
    public long propose(byte[] command, Completion<byte[]> completion) {
        if (role != Role.LEADER) {
            completion.fail(new RaftException.NotLeader(leader));
            return -1;
        }
        long index = appendLocal(command);
        pending.put(index, new Pending(term, completion));
        for (int peer : peers) {
            sendAppend(peer, false);
        }
        maybeCommit();
        return index;
    }

    /**
     * Returns queued outgoing messages. Storage is synced first, because a message may promise
     * something (a vote, an accepted entry) that has to survive a crash.
     */
    public List<Message> drainMessages() {
        storage.sync();
        if (outbox.isEmpty()) {
            return List.of();
        }
        List<Message> out = List.copyOf(outbox);
        outbox.clear();
        return out;
    }

    // ---------------------------------------------------------------- elections

    private void startPreVote() {
        // Pre-vote (Raft thesis §9.6): only bump the term if a majority would actually vote for us.
        // Without it, a node that was cut off keeps raising its term and knocks out a healthy leader
        // as soon as it reconnects.
        role = Role.PRE_CANDIDATE;
        leader = HardState.NONE;
        votesFor.clear();
        votesAgainst.clear();
        votesFor.add(id);
        resetElectionTimer();
        notifyRole();
        if (votesFor.size() >= quorum()) {
            becomeCandidate();
            return;
        }
        for (int peer : peers) {
            send(new RequestVote(id, peer, term + 1, storage.lastIndex(), lastLogTerm(), true));
        }
    }

    private void becomeCandidate() {
        term++;
        votedFor = id;
        persistHardState();
        role = Role.CANDIDATE;
        leader = HardState.NONE;
        votesFor.clear();
        votesAgainst.clear();
        votesFor.add(id);
        resetElectionTimer();
        notifyRole();
        if (votesFor.size() >= quorum()) {
            becomeLeader();
            return;
        }
        for (int peer : peers) {
            send(new RequestVote(id, peer, term, storage.lastIndex(), lastLogTerm(), false));
        }
    }

    private void handleRequestVote(RequestVote m) {
        boolean logOk = m.lastLogTerm() > lastLogTerm()
                || (m.lastLogTerm() == lastLogTerm() && m.lastLogIndex() >= storage.lastIndex());

        boolean grant;
        if (m.preVote()) {
            // Refuse if we still hear from a live leader (or are one): there's no reason for an election.
            boolean leaderAlive = role == Role.LEADER
                    || (leader != HardState.NONE && electionElapsed < config.electionTicks());
            grant = m.term() > term && logOk && !leaderAlive;
        } else {
            // §5.2 and §5.4.1: one vote per term, and only for a candidate whose log is at least as current.
            boolean canVote = votedFor == m.from() || (votedFor == HardState.NONE && leader == HardState.NONE);
            grant = canVote && logOk;
            if (grant) {
                votedFor = m.from();
                persistHardState();
                electionElapsed = 0;
            }
        }
        long replyTerm = m.preVote() && grant ? m.term() : term;
        send(new VoteResponse(id, m.from(), replyTerm, grant, m.preVote()));
    }

    private void handleVoteResponse(VoteResponse m) {
        // A granted pre-vote carries the term we asked for; anything else is from an older round.
        boolean relevant = (role == Role.PRE_CANDIDATE && m.preVote() && (!m.granted() || m.term() == term + 1))
                || (role == Role.CANDIDATE && !m.preVote() && m.term() == term);
        if (!relevant) {
            return;
        }
        if (m.granted()) {
            votesFor.add(m.from());
        } else {
            votesAgainst.add(m.from());
        }
        if (votesFor.size() >= quorum()) {
            if (role == Role.PRE_CANDIDATE) {
                becomeCandidate();
            } else {
                becomeLeader();
            }
        } else if (votesAgainst.size() >= quorum()) {
            becomeFollower(term, HardState.NONE);
        }
    }

    private void becomeLeader() {
        role = Role.LEADER;
        leader = id;
        heartbeatElapsed = 0;
        electionElapsed = 0;
        progress.clear();
        for (int peer : peers) {
            Progress p = new Progress(storage.lastIndex() + 1);
            p.recentlyActive = true;
            progress.put(peer, p);
        }
        notifyRole();
        // §8: commit a no-op from the new term right away. Until something from this term commits,
        // the leader can't tell which older entries are committed (see maybeCommit).
        appendLocal(Entry.NOOP);
        for (int peer : peers) {
            sendAppend(peer, true);
        }
        maybeCommit();
    }

    private void becomeFollower(long newTerm, int newLeader) {
        boolean wasLeader = role == Role.LEADER;
        if (newTerm != term) {
            term = newTerm;
            votedFor = HardState.NONE;
            persistHardState();
        }
        role = Role.FOLLOWER;
        leader = newLeader;
        progress.clear();
        resetElectionTimer();
        notifyRole();
        if (wasLeader && !pending.isEmpty()) {
            // These entries might still commit under the next leader, or might be overwritten.
            // We can't know which, so tell callers the outcome is unknown and let them retry safely.
            List<Pending> lost = new ArrayList<>(pending.values());
            pending.clear();
            for (Pending p : lost) {
                p.completion().fail(new RaftException.OutcomeUnknown("lost leadership before commit"));
            }
        }
    }

    // ---------------------------------------------------------------- replication

    private void handleAppendEntries(AppendEntries m) {
        acceptLeader(m.from());

        long prevIndex = m.prevLogIndex();
        long prevTerm = m.prevLogTerm();
        List<Entry> entries = m.entries();

        // Anything at or before our snapshot is committed, so it already matches the leader. Skip it.
        long snapIndex = storage.firstIndex() - 1;
        if (prevIndex < snapIndex) {
            int skip = (int) Math.min(entries.size(), snapIndex - prevIndex);
            entries = entries.subList(skip, entries.size());
            if (prevIndex + skip < snapIndex) {
                send(new AppendResponse(id, m.from(), term, true, snapIndex, 0, 0, 0, m.readSeq()));
                return;
            }
            prevIndex = snapIndex;
            prevTerm = storage.term(snapIndex);
        }

        // §5.3 consistency check, with the conflict hint from the paper's "fast backup" note so the
        // leader skips a whole term per round trip instead of one entry.
        if (prevIndex > storage.lastIndex()) {
            send(new AppendResponse(id, m.from(), term, false, 0, m.prevLogIndex(), -1, storage.lastIndex() + 1, m.readSeq()));
            return;
        }
        if (storage.term(prevIndex) != prevTerm) {
            long conflictTerm = storage.term(prevIndex);
            long first = prevIndex;
            while (first > storage.firstIndex() && storage.term(first - 1) == conflictTerm) {
                first--;
            }
            send(new AppendResponse(id, m.from(), term, false, 0, m.prevLogIndex(), conflictTerm, first, m.readSeq()));
            return;
        }

        // Skip entries we already have; truncate at the first real conflict and append the rest.
        int i = 0;
        for (; i < entries.size(); i++) {
            Entry e = entries.get(i);
            if (e.index() > storage.lastIndex()) {
                break;
            }
            if (storage.term(e.index()) != e.term()) {
                if (e.index() <= commitIndex) {
                    throw new IllegalStateException("node " + id + " asked to overwrite committed entry " + e.index());
                }
                storage.truncateFrom(e.index());
                break;
            }
        }
        if (i < entries.size()) {
            storage.append(entries.subList(i, entries.size()));
        }

        // Only trust the leader's commit index up to what this message proved we share with it.
        long lastNew = prevIndex + entries.size();
        if (m.leaderCommit() > commitIndex) {
            commitIndex = Math.min(m.leaderCommit(), lastNew);
            applyCommitted();
        }
        send(new AppendResponse(id, m.from(), term, true, lastNew, 0, 0, 0, m.readSeq()));
    }

    private void acceptLeader(int from) {
        if (role != Role.FOLLOWER) {
            // A candidate that hears from a leader of its own term gives up (§5.2).
            becomeFollower(term, from);
        }
        if (leader != from) {
            leader = from;
            notifyRole();
        }
        electionElapsed = 0;
    }

    private void handleInstallSnapshot(InstallSnapshot m) {
        acceptLeader(m.from());
        if (m.lastIndex() > commitIndex) {
            storage.saveSnapshot(new Snapshot(m.lastIndex(), m.lastTerm(), m.data()));
            stateMachine.restore(m.data());
            commitIndex = m.lastIndex();
            lastApplied = m.lastIndex();
        }
        // if we'd already committed past it, the snapshot holds nothing new for us
        send(new SnapshotResponse(id, m.from(), term, m.lastIndex(), m.readSeq()));
    }

    private void handleSnapshotResponse(SnapshotResponse m) {
        if (role != Role.LEADER) {
            return;
        }
        Progress p = progress.get(m.from());
        p.recentlyActive = true;
        p.inflight = false;
        p.ackedReadSeq = Math.max(p.ackedReadSeq, m.readSeq());
        if (m.matchIndex() > p.match) {
            p.match = m.matchIndex();
        }
        p.next = Math.max(p.next, p.match + 1);
        maybeCommit();
        if (p.next <= storage.lastIndex()) {
            sendAppend(m.from(), false);
        }
    }

    private void handleAppendResponse(AppendResponse m) {
        if (role != Role.LEADER) {
            return;
        }
        Progress p = progress.get(m.from());
        p.recentlyActive = true;
        p.inflight = false;
        p.ackedReadSeq = Math.max(p.ackedReadSeq, m.readSeq());

        if (m.success()) {
            if (m.matchIndex() > p.match) {
                p.match = m.matchIndex();
            }
            p.next = Math.max(p.next, p.match + 1);
            maybeCommit();
        } else {
            if (m.rejectedPrevIndex() < p.match) {
                return; // reply to an old request we've already moved past
            }
            long next = m.conflictIndex();
            if (m.conflictTerm() >= 0) {
                long last = lastIndexOfTerm(m.conflictTerm());
                if (last > 0) {
                    next = last + 1;
                }
            }
            p.next = Math.max(p.match + 1, Math.min(next, storage.lastIndex() + 1));
        }
        if (p.next <= storage.lastIndex() || !m.success()) {
            sendAppend(m.from(), false);
        }
    }

    private void sendAppend(int peer, boolean force) {
        Progress p = progress.get(peer);
        if (p.inflight && !force) {
            return;
        }
        if (p.next < storage.firstIndex()) {
            // the entries this follower needs are gone; ship the snapshot instead
            Snapshot snap = storage.snapshot();
            send(new InstallSnapshot(id, peer, term, snap.lastIndex(), snap.lastTerm(), snap.data(), 0));
            p.inflight = true;
            return;
        }
        long prev = p.next - 1;
        long last = Math.min(storage.lastIndex(), prev + config.maxEntriesPerAppend());
        List<Entry> entries = storage.entries(p.next, last);
        send(new AppendEntries(id, peer, term, prev, storage.term(prev), entries, commitIndex, 0));
        p.inflight = true;
    }

    /**
     * §5.3/§5.4: advance the commit index to the highest entry stored on a majority.
     */
    private void maybeCommit() {
        long n = quorumMatchIndex();
        // §5.4.2 (the paper's Figure 8): only count replicas for entries from the current term.
        // An old-term entry on a majority can still be overwritten by a future leader; it becomes
        // committed indirectly once an entry from this term commits after it.
        if (n > commitIndex && storage.term(n) == term) {
            commitIndex = n;
            applyCommitted();
            // let followers learn the new commit index promptly
            for (int peer : peers) {
                sendAppend(peer, false);
            }
        }
    }

    private long quorumMatchIndex() {
        long[] matches = new long[peers.length + 1];
        matches[0] = storage.lastIndex();
        int i = 1;
        for (int peer : peers) {
            matches[i++] = progress.get(peer).match;
        }
        Arrays.sort(matches);
        // with 5 nodes sorted ascending, index 2 is held by at least 3 of them
        return matches[matches.length - quorum()];
    }

    private void applyCommitted() {
        while (lastApplied < commitIndex) {
            lastApplied++;
            Entry e = storage.entry(lastApplied);
            byte[] result = e.isNoop() ? null : stateMachine.apply(e);
            observer.applied(id, e);
            Pending p = pending.remove(lastApplied);
            if (p != null) {
                if (p.term() == e.term()) {
                    p.completion().complete(result);
                } else {
                    p.completion().fail(new RaftException.OutcomeUnknown("entry was replaced by another leader"));
                }
            }
        }
        maybeSnapshot();
    }

    /** Compacts the log once enough entries have been applied since the last snapshot. */
    private void maybeSnapshot() {
        long snapIndex = storage.firstIndex() - 1;
        if (lastApplied - snapIndex >= config.snapshotEvery()) {
            storage.saveSnapshot(new Snapshot(lastApplied, storage.term(lastApplied), stateMachine.snapshot()));
        }
    }

    // ---------------------------------------------------------------- helpers

    private long appendLocal(byte[] data) {
        long index = storage.lastIndex() + 1;
        storage.append(List.of(new Entry(term, index, clock.getAsLong(), data)));
        return index;
    }

    private long lastIndexOfTerm(long t) {
        for (long i = storage.lastIndex(); i >= storage.firstIndex(); i--) {
            long ti = storage.term(i);
            if (ti == t) {
                return i;
            }
            if (ti < t) {
                break;
            }
        }
        return 0;
    }

    private long lastLogTerm() {
        return storage.term(storage.lastIndex());
    }

    private int quorum() {
        return (peers.length + 1) / 2 + 1;
    }

    private void resetElectionTimer() {
        electionElapsed = 0;
        randomizedElectionTimeout = config.electionTicks() + random.nextInt(config.electionTicks());
    }

    private void persistHardState() {
        storage.setHardState(new HardState(term, votedFor));
    }

    private void send(Message m) {
        outbox.add(m);
    }

    private void notifyRole() {
        observer.roleChanged(id, role, term, leader);
    }

    // ---------------------------------------------------------------- read-only views

    public int id() {
        return id;
    }

    public long term() {
        return term;
    }

    public Role role() {
        return role;
    }

    public int leader() {
        return leader;
    }

    public long commitIndex() {
        return commitIndex;
    }

    public long lastApplied() {
        return lastApplied;
    }

    public long lastIndex() {
        return storage.lastIndex();
    }
}
