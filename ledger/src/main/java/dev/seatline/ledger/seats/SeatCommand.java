package dev.seatline.ledger.seats;

import java.util.List;

/** Every change the seat ledger accepts. Each one goes through the Raft log. */
public sealed interface SeatCommand {

    /** Puts an event's seats on the ledger, all free. {@code limitPerFan} caps holds plus tickets per fan. */
    record CreateEvent(String eventId, List<String> seatIds, int limitPerFan) implements SeatCommand {}

    /** Holds every listed seat for one fan, or none of them. */
    record Hold(String eventId, List<String> seatIds, String fanId, String holdId, long ttlMillis)
            implements SeatCommand {}

    /** Turns a live hold into tickets once payment has gone through. */
    record Confirm(String holdId, String paymentRef) implements SeatCommand {}

    /** The fan gave up their hold. */
    record Release(String holdId) implements SeatCommand {}

    /** Frees every hold whose time ran out (sent periodically by the API). */
    record ExpireDue() implements SeatCommand {}

    /** Moves a ticket to another fan. The ticket's version bumps, so the old QR code stops working. */
    record Transfer(String ticketId, String fromFan, String toFan) implements SeatCommand {}

    /** The fan hands a ticket back at face value; the seat is free again. */
    record Return(String ticketId, String fanId) implements SeatCommand {}
}
