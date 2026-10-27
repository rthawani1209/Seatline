package dev.seatline.ledger.check;

import dev.seatline.ledger.seats.SeatCommand;
import dev.seatline.ledger.seats.SeatLedger;
import dev.seatline.ledger.seats.SeatLedger.SeatView;
import dev.seatline.ledger.seats.SeatResult;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The seat ledger's sequential specification, for the linearizability checker. It simply runs the
 * real {@link SeatLedger} code on a single copy, so the check asks: "could one ledger, applying the
 * operations one at a time in some order consistent with real time, have produced exactly what the
 * clients saw?"
 *
 * <p>Outputs are compared as short summaries (outcome plus seat or ticket ids). Hold expiry times
 * are left out because the cluster stamps real times while the model runs at time 0; the chaos
 * workload's holds last longer than the run, so none expire.
 */
public final class SeatModel implements Model<SeatModel.State> {

    public record Write(SeatCommand command) {}

    public record Read(String eventId) {}

    /** Ledger state as snapshot bytes, so it can be compared and cached by value. */
    public record State(byte[] bytes) {
        @Override
        public boolean equals(Object o) {
            return o instanceof State s && Arrays.equals(bytes, s.bytes);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(bytes);
        }
    }

    private final List<SeatCommand> setup;

    /** @param setup commands applied before the history starts (like creating the event) */
    public SeatModel(List<SeatCommand> setup) {
        this.setup = setup;
    }

    @Override
    public State initial() {
        SeatLedger ledger = new SeatLedger();
        for (SeatCommand c : setup) {
            ledger.execute(c, 0);
        }
        return new State(ledger.snapshot());
    }

    @Override
    public State step(State state, Object input, Object output) {
        SeatLedger ledger = new SeatLedger();
        ledger.restore(state.bytes());
        switch (input) {
            case Write w -> {
                String actual = summarize(ledger.execute(w.command(), 0));
                if (output != null && !output.equals(actual)) {
                    return null;
                }
                return new State(ledger.snapshot());
            }
            case Read r -> {
                if (output != null && !output.equals(summarize(ledger.seatMap(r.eventId(), 0)))) {
                    return null;
                }
                return state;
            }
            default -> throw new IllegalArgumentException("unknown input " + input);
        }
    }

    public static String summarize(SeatResult r) {
        return r.outcome() + (r.ids().isEmpty() ? "" : " " + String.join(",", r.ids()));
    }

    public static String summarize(List<SeatView> seats) {
        if (seats == null) {
            return "NO_SUCH_EVENT";
        }
        return seats.stream()
                .map(v -> v.seatId() + "=" + switch (v.status()) {
                    case FREE -> "free";
                    case HELD -> "held:" + v.holdId();
                    case SOLD -> "sold:" + v.ticketId();
                })
                .collect(Collectors.joining(" "));
    }
}
