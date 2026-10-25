package dev.seatline.ledger.sim;

import dev.seatline.ledger.raft.Entry;
import dev.seatline.ledger.raft.StateMachine;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * A tiny state machine for testing Raft itself: it echoes each command back and keeps a running
 * hash of everything applied, so two replicas with the same state are easy to compare.
 */
public final class ChecksumStateMachine implements StateMachine {

    private long applied;
    private long hash = 17;

    @Override
    public byte[] apply(Entry entry) {
        applied++;
        hash = hash * 31 + Arrays.hashCode(entry.data());
        return entry.data();
    }

    @Override
    public byte[] snapshot() {
        return ByteBuffer.allocate(16).putLong(applied).putLong(hash).array();
    }

    @Override
    public void restore(byte[] snapshot) {
        ByteBuffer b = ByteBuffer.wrap(snapshot);
        applied = b.getLong();
        hash = b.getLong();
    }

    public long applied() {
        return applied;
    }

    public long hash() {
        return hash;
    }
}
