package dev.seatline.ledger.seats;

import java.util.List;

/**
 * What the ledger says about a command.
 *
 * @param expiresAt for a hold, when it runs out (epoch millis)
 * @param ids seats held, tickets issued, or (on SEATS_TAKEN) the seats someone else has
 */
public record SeatResult(Outcome outcome, long expiresAt, List<String> ids) {

    public enum Outcome {
        OK,
        SEATS_TAKEN,
        LIMIT_REACHED,
        NO_SUCH_EVENT,
        NO_SUCH_SEAT,
        ALREADY_EXISTS,
        NO_SUCH_HOLD,
        HOLD_EXPIRED,
        NO_SUCH_TICKET,
        NOT_OWNER,
        /** A retry of a request older than the client's latest one; its answer is no longer kept. */
        STALE_REQUEST,
        BAD_REQUEST
    }

    public static SeatResult of(Outcome outcome) {
        return new SeatResult(outcome, 0, List.of());
    }

    public static SeatResult ok(List<String> ids) {
        return new SeatResult(Outcome.OK, 0, ids);
    }

    public boolean ok() {
        return outcome == Outcome.OK;
    }
}
