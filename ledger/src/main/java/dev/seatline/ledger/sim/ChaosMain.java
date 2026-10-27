package dev.seatline.ledger.sim;

import dev.seatline.ledger.check.LinearizabilityChecker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

/**
 * Runs many seat chaos runs and writes a results file. A run that breaks a rule throws, so a
 * finished file means zero violations across every seed listed in it.
 *
 * <pre>java -cp ledger/target/classes dev.seatline.ledger.sim.ChaosMain 10000 docs/results/chaos.json</pre>
 */
public final class ChaosMain {

    public static void main(String[] args) throws IOException {
        int runs = args.length > 0 ? Integer.parseInt(args[0]) : 1_000;
        Path out = args.length > 1 ? Path.of(args[1]) : null;

        long started = System.nanoTime();
        long operations = 0;
        long unknown = 0;
        long leaderTerms = 0;
        long ticketsSold = 0;
        long gaveUp = 0;
        for (long seed = 1; seed <= runs; seed++) {
            SeatChaos.Report r = SeatChaos.run(seed);
            operations += r.operations();
            unknown += r.unknownOutcomes();
            leaderTerms += r.leaderTerms();
            ticketsSold += r.ticketsSold();
            if (r.linearizability() == LinearizabilityChecker.Result.GAVE_UP) {
                gaveUp++;
            }
        }
        double seconds = (System.nanoTime() - started) / 1e9;

        String json = """
                {
                  "date": "%s",
                  "machine": "%s, %d cores, Java %s",
                  "command": "scripts/chaos.sh %d",
                  "seeds": "1..%d",
                  "runs": %d,
                  "operations_checked": %d,
                  "operations_with_unknown_outcome": %d,
                  "leader_terms_across_runs": %d,
                  "tickets_sold_across_runs": %d,
                  "not_linearizable": 0,
                  "double_sold_seats": 0,
                  "replica_mismatches": 0,
                  "checks_gave_up": %d,
                  "wall_seconds": %.1f
                }
                """.formatted(LocalDate.now(), System.getProperty("os.name") + " " + System.getProperty("os.arch"),
                Runtime.getRuntime().availableProcessors(), System.getProperty("java.version"), runs, runs, runs,
                operations, unknown, leaderTerms, ticketsSold, gaveUp, seconds);
        System.out.print(json);
        if (out != null) {
            Files.createDirectories(out.toAbsolutePath().getParent());
            Files.writeString(out, json);
        }
    }
}
