package dev.seatline.ledger.check;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static dev.seatline.ledger.check.LinearizabilityChecker.Result.LINEARIZABLE;
import static dev.seatline.ledger.check.LinearizabilityChecker.Result.NOT_LINEARIZABLE;
import static org.assertj.core.api.Assertions.assertThat;

class LinearizabilityCheckerTest {

    /** A single register: write(v) and read() -> v. */
    record Write(int value) {}

    record Read() {}

    static final Model<Integer> REGISTER = new Model<>() {
        @Override
        public Integer initial() {
            return 0;
        }

        @Override
        public Integer step(Integer state, Object input, Object output) {
            if (input instanceof Write w) {
                return w.value();
            }
            return output == null || output.equals(state) ? state : null;
        }
    };

    static Operation op(int client, Object in, Object out, long start, long end) {
        return new Operation(client, in, out, start, end);
    }

    @Test
    void sequentialHistory() {
        List<Operation> h = List.of(
                op(1, new Write(1), "ok", 0, 10),
                op(2, new Read(), 1, 20, 30));
        assertThat(LinearizabilityChecker.check(REGISTER, h)).isEqualTo(LINEARIZABLE);
    }

    @Test
    void staleReadAfterWriteFinishedIsCaught() {
        // the write finished before the read began, so the read must see it
        List<Operation> h = List.of(
                op(1, new Write(1), "ok", 0, 10),
                op(2, new Read(), 0, 20, 30));
        assertThat(LinearizabilityChecker.check(REGISTER, h)).isEqualTo(NOT_LINEARIZABLE);
    }

    @Test
    void overlappingReadMaySeeEitherValue() {
        for (int seen : new int[] {0, 1}) {
            List<Operation> h = List.of(
                    op(1, new Write(1), "ok", 0, 50),
                    op(2, new Read(), seen, 10, 20));
            assertThat(LinearizabilityChecker.check(REGISTER, h)).isEqualTo(LINEARIZABLE);
        }
    }

    @Test
    void readsCantGoBackInTime() {
        // once a read has seen the new value, a later read can't see the old one
        List<Operation> h = List.of(
                op(1, new Write(1), "ok", 0, 100),
                op(2, new Read(), 1, 10, 20),
                op(3, new Read(), 0, 30, 40));
        assertThat(LinearizabilityChecker.check(REGISTER, h)).isEqualTo(NOT_LINEARIZABLE);
    }

    @Test
    void unknownWriteMayOrMayNotHaveHappened() {
        List<Operation> took = List.of(
                Operation.unknown(1, new Write(7), 0),
                op(2, new Read(), 7, 50, 60));
        List<Operation> didnt = List.of(
                Operation.unknown(1, new Write(7), 0),
                op(2, new Read(), 0, 50, 60));
        assertThat(LinearizabilityChecker.check(REGISTER, took)).isEqualTo(LINEARIZABLE);
        assertThat(LinearizabilityChecker.check(REGISTER, didnt)).isEqualTo(LINEARIZABLE);
    }

    @Test
    void largeRandomSequentialHistoryChecksFast() {
        // a history that came from a real single-copy register is always linearizable
        Random rnd = new Random(1);
        List<Operation> h = new ArrayList<>();
        int value = 0;
        long t = 0;
        for (int i = 0; i < 2_000; i++) {
            if (rnd.nextBoolean()) {
                value = rnd.nextInt(5);
                h.add(op(i % 4, new Write(value), "ok", t, t + 5));
            } else {
                h.add(op(i % 4, new Read(), value, t, t + 5));
            }
            t += 3; // neighbours overlap a little
        }
        assertThat(LinearizabilityChecker.check(REGISTER, h)).isEqualTo(LINEARIZABLE);
    }
}
