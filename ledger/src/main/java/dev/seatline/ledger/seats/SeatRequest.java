package dev.seatline.ledger.seats;

/**
 * A command plus the client's id and sequence number. The ledger remembers the last sequence
 * number and answer per client, so a retried request (say, after a timeout) is applied once and
 * the retry gets the original answer back.
 *
 * <p>{@code clientId} 0 means "no deduplication", for idempotent housekeeping like {@link
 * SeatCommand.ExpireDue}.
 */
public record SeatRequest(long clientId, long seq, SeatCommand command) {}
