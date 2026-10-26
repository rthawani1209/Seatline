package dev.seatline.ledger.check;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Decides whether a concurrent history is linearizable: whether there's a single order of the
 * operations that (1) respects real time, so an operation that finished before another started
 * comes first, and (2) gives exactly the outputs the clients saw when run one at a time through the
 * model.
 *
 * <p>This is the Wing and Gong search with Lowe's memoization, the same approach as Knossos and
 * Porcupine. It walks the history keeping a set of operations already placed, tries to place each
 * operation that's currently allowed to go next, and backtracks when it's stuck. A cache of
 * (operations placed, resulting state) pairs it has already explored keeps the search from blowing
 * up in practice, although the problem is NP-complete in general.
 */
public final class LinearizabilityChecker {

    public enum Result { LINEARIZABLE, NOT_LINEARIZABLE, GAVE_UP }

    private LinearizabilityChecker() {}

    private static final class Event {
        final int opId;
        final Operation op;
        final boolean isCall;
        Event match;
        Event prev;
        Event next;

        Event(int opId, Operation op, boolean isCall) {
            this.opId = opId;
            this.op = op;
            this.isCall = isCall;
        }
    }

    private record CacheKey(BitSet placed, Object state) {}

    private record Frame(Event call, Object stateBefore) {}

    public static <S> Result check(Model<S> model, List<Operation> history) {
        return check(model, history, 50_000_000L);
    }

    @SuppressWarnings("unchecked")
    public static <S> Result check(Model<S> model, List<Operation> history, long maxSteps) {
        Event head = buildEventList(history);
        BitSet placed = new BitSet(history.size());
        Set<CacheKey> cache = new HashSet<>();
        Deque<Frame> stack = new ArrayDeque<>();
        S state = model.initial();
        Event entry = head.next;
        long steps = 0;

        while (head.next != null) {
            if (++steps > maxSteps) {
                return Result.GAVE_UP;
            }
            if (entry.isCall) {
                S next = model.step(state, entry.op.input(), entry.op.output());
                if (next != null) {
                    BitSet withThis = (BitSet) placed.clone();
                    withThis.set(entry.opId);
                    if (cache.add(new CacheKey(withThis, next))) {
                        // place this operation next and start again from the earliest remaining event
                        stack.push(new Frame(entry, state));
                        state = next;
                        placed.set(entry.opId);
                        lift(entry);
                        entry = head.next;
                        continue;
                    }
                }
                entry = entry.next;
            } else {
                // Hit a return: its operation must have been placed by now and wasn't. Backtrack.
                if (stack.isEmpty()) {
                    return Result.NOT_LINEARIZABLE;
                }
                Frame f = stack.pop();
                state = (S) f.stateBefore();
                placed.clear(f.call().opId);
                unlift(f.call());
                entry = f.call().next;
            }
        }
        return Result.LINEARIZABLE;
    }

    /** Calls and returns in time order; at equal times calls go first, treating the two as overlapping. */
    private static Event buildEventList(List<Operation> history) {
        List<Event> events = new ArrayList<>(history.size() * 2);
        for (int i = 0; i < history.size(); i++) {
            Operation op = history.get(i);
            if (op.returned() < op.invoked()) {
                throw new IllegalArgumentException("operation returns before it starts: " + op);
            }
            Event call = new Event(i, op, true);
            Event ret = new Event(i, op, false);
            call.match = ret;
            ret.match = call;
            events.add(call);
            events.add(ret);
        }
        events.sort(Comparator.comparingLong((Event e) -> e.isCall ? e.op.invoked() : e.op.returned())
                .thenComparing(e -> !e.isCall));
        Event head = new Event(-1, null, false);
        Event prev = head;
        for (Event e : events) {
            prev.next = e;
            e.prev = prev;
            prev = e;
        }
        return head;
    }

    private static void lift(Event call) {
        call.prev.next = call.next;
        call.next.prev = call.prev;
        Event ret = call.match;
        ret.prev.next = ret.next;
        if (ret.next != null) {
            ret.next.prev = ret.prev;
        }
    }

    private static void unlift(Event call) {
        Event ret = call.match;
        ret.prev.next = ret;
        if (ret.next != null) {
            ret.next.prev = ret;
        }
        call.prev.next = call;
        call.next.prev = call;
    }
}
