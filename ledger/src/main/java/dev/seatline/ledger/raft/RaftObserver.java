package dev.seatline.ledger.raft;

/** Hooks for metrics, the dashboard and the simulator's safety checks. */
public interface RaftObserver {

    RaftObserver NONE = new RaftObserver() {};

    default void roleChanged(int node, Role role, long term, int leader) {}

    default void applied(int node, Entry entry) {}
}
