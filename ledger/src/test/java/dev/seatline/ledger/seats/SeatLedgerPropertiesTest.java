package dev.seatline.ledger.seats;

import dev.seatline.ledger.seats.SeatLedger.SeatStatus;
import dev.seatline.ledger.seats.SeatLedger.SeatView;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.Size;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Random command sequences against the ledger. Whatever happens, the bookkeeping stays consistent,
 * a seat never ends up with two owners, and a snapshot restores to the same ledger.
 */
class SeatLedgerPropertiesTest {

    static final List<String> SEATS = List.of("A1", "A2", "A3", "A4", "A5");
    static final List<String> FANS = List.of("ana", "ben", "cy");

    record Step(SeatCommand command, long advance) {}

    @Provide
    Arbitrary<Step> steps() {
        Arbitrary<String> holdIds = Arbitraries.integers().between(1, 12).map(i -> "h" + i);
        Arbitrary<String> ticketIds = Combinators.combine(holdIds, Arbitraries.of(SEATS)).as((h, s) -> "T-" + h + "-" + s);
        Arbitrary<String> fans = Arbitraries.of(FANS);
        Arbitrary<SeatCommand> commands = Arbitraries.oneOf(
                Combinators.combine(Arbitraries.of(SEATS).list().ofMinSize(1).ofMaxSize(3).uniqueElements(), fans, holdIds,
                        Arbitraries.longs().between(1, 400)).as((seats, fan, h, ttl) -> new SeatCommand.Hold("ev", seats, fan, h, ttl)),
                holdIds.map(h -> new SeatCommand.Confirm(h, "pay")),
                holdIds.map(SeatCommand.Release::new),
                Arbitraries.just(new SeatCommand.ExpireDue()),
                Combinators.combine(ticketIds, fans, fans).as(SeatCommand.Transfer::new),
                Combinators.combine(ticketIds, fans).as(SeatCommand.Return::new));
        return Combinators.combine(commands, Arbitraries.longs().between(0, 150)).as(Step::new);
    }

    @Property(tries = 500)
    void invariantsHoldAfterAnySequence(@ForAll @Size(max = 60) List<@net.jqwik.api.From("steps") Step> steps) {
        SeatLedger ledger = new SeatLedger();
        ledger.execute(new SeatCommand.CreateEvent("ev", SEATS, 3), 0);
        long now = 0;
        Map<String, String> ownerBySeat = new HashMap<>();
        for (Step step : steps) {
            now += step.advance();
            SeatResult r = ledger.execute(step.command(), now);
            ledger.checkInvariants();

            // a fan never gets a ticket for a seat that's already someone's ticket
            if (r.ok() && step.command() instanceof SeatCommand.Confirm) {
                for (String ticketId : r.ids()) {
                    SeatLedger.Ticket t = ledger.ticket(ticketId);
                    assertThat(ownerBySeat.put(t.seatId(), ticketId)).isNull();
                }
            }
            if (r.ok() && step.command() instanceof SeatCommand.Return ret) {
                ownerBySeat.remove(ret.ticketId().substring(ret.ticketId().lastIndexOf('-') + 1));
            }
            long sold = ledger.seatMap("ev", now).stream().filter(v -> v.status() == SeatStatus.SOLD).count();
            assertThat(sold).isEqualTo(ownerBySeat.size());

            // nobody ever has more seats (live holds plus tickets) than the limit of 3
            Map<String, Integer> perFan = new HashMap<>();
            for (SeatView v : ledger.seatMap("ev", now)) {
                String fan = switch (v.status()) {
                    case HELD -> ledger.hold(v.holdId()).fanId();
                    case SOLD -> ledger.ticket(v.ticketId()).ownerFan();
                    case FREE -> null;
                };
                if (fan != null) {
                    perFan.merge(fan, 1, Integer::sum);
                }
            }
            assertThat(perFan.values()).allMatch(n -> n <= 3);
        }
    }

    @Property(tries = 300)
    void snapshotRestoresSameLedger(@ForAll @Size(max = 40) List<@net.jqwik.api.From("steps") Step> steps) {
        SeatLedger ledger = new SeatLedger();
        ledger.execute(new SeatCommand.CreateEvent("ev", SEATS, 3), 0);
        long now = 0;
        for (Step step : steps) {
            now += step.advance();
            ledger.execute(step.command(), now);
        }
        SeatLedger copy = new SeatLedger();
        copy.restore(ledger.snapshot());
        copy.checkInvariants();
        List<SeatView> a = ledger.seatMap("ev", now);
        assertThat(copy.seatMap("ev", now)).isEqualTo(a);
        assertThat(copy.snapshot()).isEqualTo(ledger.snapshot());
    }

    @Property(tries = 300)
    void holdsNeverOverlap(@ForAll @Size(max = 60) List<@net.jqwik.api.From("steps") Step> steps) {
        SeatLedger ledger = new SeatLedger();
        ledger.execute(new SeatCommand.CreateEvent("ev", SEATS, 5), 0);
        long now = 0;
        for (Step step : steps) {
            now += step.advance();
            ledger.execute(step.command(), now);
            // each held seat names exactly one live hold, and that hold lists the seat
            for (SeatView v : ledger.seatMap("ev", now)) {
                if (v.status() == SeatStatus.HELD) {
                    assertThat(ledger.hold(v.holdId()).seats()).contains(v.seatId());
                }
            }
        }
    }
}
