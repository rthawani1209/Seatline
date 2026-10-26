package dev.seatline.ledger.sim;

import dev.seatline.ledger.check.LinearizabilityChecker;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fans buying seats while servers crash and the network misbehaves. Each run throws if the history
 * isn't linearizable, a seat is sold twice, or the replicas disagree at the end.
 */
class SeatChaosTest {

    static final int RUNS = Integer.getInteger("seatline.chaos.runs", 100);

    @Test
    void noDoubleSalesUnderChaos() {
        int ops = 0;
        int unknown = 0;
        int gaveUp = 0;
        for (long seed = 1; seed <= RUNS; seed++) {
            SeatChaos.Report r = SeatChaos.run(seed);
            ops += r.operations();
            unknown += r.unknownOutcomes();
            if (r.linearizability() == LinearizabilityChecker.Result.GAVE_UP) {
                gaveUp++;
            }
        }
        System.out.printf("chaos: %d runs, %d operations checked, %d with unknown outcome, %d checks gave up%n",
                RUNS, ops, unknown, gaveUp);
        assertThat(gaveUp).isZero();
    }
}
