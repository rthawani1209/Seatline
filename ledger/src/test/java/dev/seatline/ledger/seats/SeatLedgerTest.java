package dev.seatline.ledger.seats;

import dev.seatline.ledger.raft.Entry;
import dev.seatline.ledger.seats.SeatCommand.Confirm;
import dev.seatline.ledger.seats.SeatCommand.CreateEvent;
import dev.seatline.ledger.seats.SeatCommand.ExpireDue;
import dev.seatline.ledger.seats.SeatCommand.Hold;
import dev.seatline.ledger.seats.SeatCommand.Release;
import dev.seatline.ledger.seats.SeatCommand.Return;
import dev.seatline.ledger.seats.SeatCommand.Transfer;
import dev.seatline.ledger.seats.SeatLedger.SeatStatus;
import dev.seatline.ledger.seats.SeatResult.Outcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SeatLedgerTest {

    static final long MIN = 60_000;

    SeatLedger ledger;

    @BeforeEach
    void setUp() {
        ledger = new SeatLedger();
        assertThat(ledger.execute(new CreateEvent("show", List.of("A1", "A2", "A3", "A4"), 2), 0).ok()).isTrue();
    }

    private SeatResult hold(String fan, String holdId, long now, String... seats) {
        return ledger.execute(new Hold("show", List.of(seats), fan, holdId, 5 * MIN), now);
    }

    private SeatStatus status(String seat, long now) {
        return ledger.seatMap("show", now).stream().filter(v -> v.seatId().equals(seat)).findFirst().orElseThrow().status();
    }

    @Test
    void holdThenConfirmSellsTheSeats() {
        SeatResult h = hold("jordan", "h1", 1_000, "A1", "A2");
        assertThat(h.ok()).isTrue();
        assertThat(h.expiresAt()).isEqualTo(1_000 + 5 * MIN);
        assertThat(status("A1", 1_000)).isEqualTo(SeatStatus.HELD);

        SeatResult c = ledger.execute(new Confirm("h1", "pay_1"), 2_000);
        assertThat(c.ids()).containsExactly("T-h1-A1", "T-h1-A2");
        assertThat(status("A1", 2_000)).isEqualTo(SeatStatus.SOLD);
        assertThat(ledger.ticket("T-h1-A2").ownerFan()).isEqualTo("jordan");
        ledger.checkInvariants();
    }

    @Test
    void secondFanCantHoldATakenSeat() {
        hold("jordan", "h1", 0, "A1", "A2");
        SeatResult clash = hold("sam", "h2", 10, "A2", "A3");
        assertThat(clash.outcome()).isEqualTo(Outcome.SEATS_TAKEN);
        assertThat(clash.ids()).containsExactly("A2");
        // all or nothing: A3 must still be free
        assertThat(status("A3", 10)).isEqualTo(SeatStatus.FREE);
        ledger.checkInvariants();
    }

    @Test
    void expiredHoldFreesSeatsForTheNextFan() {
        hold("jordan", "h1", 0, "A1");
        long later = 5 * MIN + 1;
        assertThat(status("A1", later)).isEqualTo(SeatStatus.FREE);
        assertThat(hold("sam", "h2", later, "A1").ok()).isTrue();
        // jordan's payment lands too late
        assertThat(ledger.execute(new Confirm("h1", "pay"), later + 5).outcome()).isEqualTo(Outcome.NO_SUCH_HOLD);
        ledger.checkInvariants();
    }

    @Test
    void confirmAfterExpiryIsRefused() {
        hold("jordan", "h1", 0, "A1");
        assertThat(ledger.execute(new Confirm("h1", "pay"), 5 * MIN).ok()).isTrue(); // right at the deadline is fine
        hold("sam", "h2", 0, "A2");
        assertThat(ledger.execute(new Confirm("h2", "pay"), 5 * MIN + 1).outcome()).isEqualTo(Outcome.HOLD_EXPIRED);
        assertThat(status("A2", 5 * MIN + 1)).isEqualTo(SeatStatus.FREE);
        ledger.checkInvariants();
    }

    @Test
    void expireDueSweepsOnlyOverdueHolds() {
        hold("a", "h1", 0, "A1");
        hold("b", "h2", MIN, "A2");
        SeatResult swept = ledger.execute(new ExpireDue(), 5 * MIN + 10);
        assertThat(swept.ids()).containsExactly("h1");
        assertThat(ledger.hold("h2")).isNotNull();
        ledger.checkInvariants();
    }

    @Test
    void perFanLimitCountsHoldsAndTickets() {
        hold("jordan", "h1", 0, "A1");
        ledger.execute(new Confirm("h1", "p"), 1);
        assertThat(hold("jordan", "h2", 2, "A2").ok()).isTrue();
        assertThat(hold("jordan", "h3", 3, "A3").outcome()).isEqualTo(Outcome.LIMIT_REACHED);
        // releasing a hold gives the allowance back
        ledger.execute(new Release("h2"), 4);
        assertThat(hold("jordan", "h4", 5, "A3").ok()).isTrue();
    }

    @Test
    void transferMovesOwnershipAndBumpsVersion() {
        hold("jordan", "h1", 0, "A1");
        ledger.execute(new Confirm("h1", "p"), 1);
        assertThat(ledger.execute(new Transfer("T-h1-A1", "sam", "alex"), 2).outcome()).isEqualTo(Outcome.NOT_OWNER);
        assertThat(ledger.execute(new Transfer("T-h1-A1", "jordan", "sam"), 2).ok()).isTrue();
        SeatLedger.Ticket t = ledger.ticket("T-h1-A1");
        assertThat(t.ownerFan()).isEqualTo("sam");
        assertThat(t.version()).isEqualTo(2);
        ledger.checkInvariants();
    }

    @Test
    void returnFreesTheSeat() {
        hold("jordan", "h1", 0, "A1");
        ledger.execute(new Confirm("h1", "p"), 1);
        assertThat(ledger.execute(new Return("T-h1-A1", "jordan"), 2).ok()).isTrue();
        assertThat(status("A1", 2)).isEqualTo(SeatStatus.FREE);
        assertThat(ledger.ticket("T-h1-A1")).isNull();
        ledger.checkInvariants();
    }

    @Test
    void badRequestsAreRejected() {
        assertThat(hold("x", "h1", 0).outcome()).isEqualTo(Outcome.BAD_REQUEST);
        assertThat(hold("x", "h1", 0, "A1", "A1").outcome()).isEqualTo(Outcome.BAD_REQUEST);
        assertThat(hold("x", "h1", 0, "Z9").outcome()).isEqualTo(Outcome.NO_SUCH_SEAT);
        assertThat(ledger.execute(new Hold("nope", List.of("A1"), "x", "h", MIN), 0).outcome()).isEqualTo(Outcome.NO_SUCH_EVENT);
        assertThat(ledger.execute(new CreateEvent("show", List.of("B1"), 1), 0).outcome()).isEqualTo(Outcome.ALREADY_EXISTS);
    }

    @Test
    void retriedRequestAppliesOnce() {
        byte[] req = SeatCodec.encode(new SeatRequest(42, 1, new Hold("show", List.of("A1"), "jordan", "h1", MIN)));
        SeatResult first = SeatCodec.decodeResult(ledger.apply(new Entry(1, 2, 100, req)));
        SeatResult retry = SeatCodec.decodeResult(ledger.apply(new Entry(1, 3, 200, req)));
        // without dedup the retry would say ALREADY_EXISTS; instead it gets the original answer
        assertThat(retry).usingRecursiveComparison().isEqualTo(first);
        assertThat(retry.ok()).isTrue();

        byte[] newer = SeatCodec.encode(new SeatRequest(42, 2, new Release("h1")));
        ledger.apply(new Entry(1, 4, 300, newer));
        assertThat(SeatCodec.decodeResult(ledger.apply(new Entry(1, 5, 400, req))).outcome())
                .isEqualTo(Outcome.STALE_REQUEST);
    }

    @Test
    void snapshotRoundTrips() {
        hold("jordan", "h1", 0, "A1", "A2");
        hold("sam", "h2", 0, "A3");
        ledger.execute(new Confirm("h2", "p"), 1);
        ledger.apply(new Entry(1, 9, 5, SeatCodec.encode(new SeatRequest(7, 3, new Release("nope")))));

        SeatLedger copy = new SeatLedger();
        copy.restore(ledger.snapshot());
        copy.checkInvariants();
        assertThat(copy.snapshot()).isEqualTo(ledger.snapshot());
        assertThat(copy.seatMap("show", 2)).isEqualTo(ledger.seatMap("show", 2));
    }

    @Test
    void transferCountsRecipientsLiveHolds() {
        hold("jordan", "h1", 0, "A1");
        ledger.execute(new Confirm("h1", "p"), 1);
        // sam is at the limit of 2 through holds alone
        assertThat(hold("sam", "h2", 2, "A2", "A3").ok()).isTrue();
        assertThat(ledger.execute(new Transfer("T-h1-A1", "jordan", "sam"), 3).outcome())
                .isEqualTo(Outcome.LIMIT_REACHED);
        // once sam's holds run out, there's room again
        assertThat(ledger.execute(new Transfer("T-h1-A1", "jordan", "sam"), 2 + 5 * MIN + 1).ok()).isTrue();
        ledger.checkInvariants();
    }

    @Test
    void holdLengthIsCapped() {
        SeatResult tooLong = ledger.execute(
                new Hold("show", List.of("A1"), "x", "h1", SeatLedger.MAX_HOLD_TTL_MILLIS + 1), 0);
        assertThat(tooLong.outcome()).isEqualTo(Outcome.BAD_REQUEST);
        assertThat(ledger.execute(new Hold("show", List.of("A1"), "x", "h2", Long.MAX_VALUE), 1_000).outcome())
                .isEqualTo(Outcome.BAD_REQUEST);
        assertThat(ledger.execute(
                new Hold("show", List.of("A1"), "x", "h3", SeatLedger.MAX_HOLD_TTL_MILLIS), 0).ok()).isTrue();
    }

    @Test
    void garbageEntriesGetBadRequestInsteadOfCrashing() {
        byte[] valid = SeatCodec.encode(new SeatRequest(5, 1, new Hold("show", List.of("A1"), "jordan", "h1", MIN)));
        byte[] truncated = java.util.Arrays.copyOf(valid, valid.length - 3);
        byte[] unknownType = valid.clone();
        unknownType[16] = 99;
        byte[] hugeList = java.nio.ByteBuffer.allocate(21).putLong(1).putLong(1).put((byte) 1)
                .putInt(Integer.MAX_VALUE).array();
        byte[] trailing = java.util.Arrays.copyOf(valid, valid.length + 4);

        long index = 1;
        for (byte[] bad : List.of(truncated, unknownType, hugeList, trailing, new byte[] {1, 2, 3})) {
            assertThat(ledger.accepts(bad)).isFalse();
            SeatResult r = SeatCodec.decodeResult(ledger.apply(new Entry(1, index++, 0, bad)));
            assertThat(r.outcome()).isEqualTo(Outcome.BAD_REQUEST);
        }
        assertThat(ledger.accepts(new byte[0])).isFalse();
        // and the ledger still works afterwards
        assertThat(ledger.accepts(valid)).isTrue();
        assertThat(SeatCodec.decodeResult(ledger.apply(new Entry(1, index, 0, valid))).ok()).isTrue();
        ledger.checkInvariants();
    }
}
