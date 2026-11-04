package dev.seatline.api.event;

import dev.seatline.api.seats.Seats;
import dev.seatline.api.web.ApiException;
import dev.seatline.ledger.seats.SeatLedger.SeatStatus;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * "Best available": finds {@code count} free seats next to each other in one row of a tier's
 * sections. Closer rows win, then seats nearer the middle of the row. Only suggests seats; the
 * ledger still decides when the fan tries to hold them, so a suggestion can lose a race.
 */
@Component
class BestSeats {

    private record Seat(String label, String section, String row, int number, boolean accessible) {}

    private final JdbcClient db;
    private final EventService events;
    private final Seats seats;

    BestSeats(JdbcClient db, EventService events, Seats seats) {
        this.db = db;
        this.events = events;
        this.seats = seats;
    }

    List<String> pick(UUID eventId, UUID tierId, int count, boolean accessibleOnly) {
        if (count < 1 || count > 20) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_request");
        }
        EventService.Event e = events.getVisible(eventId, null);
        EventService.Tier tier = e.tiers().stream().filter(t -> t.id().equals(tierId)).findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "no_such_tier"));
        Map<String, SeatStatus> live = seats.statuses(eventId.toString());
        if (live == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "no_such_event");
        }

        // seats of the tier's sections, grouped into rows in front-to-back order
        Map<String, List<Seat>> rows = new LinkedHashMap<>();
        db.sql("""
                select st.label, s.name, st.row_label, st.number, st.accessible
                from seats st join sections s on s.id = st.section_id
                join tier_sections ts on ts.section_id = s.id
                where ts.tier_id = ? order by s.name, st.row_label, st.number""")
                .param(tier.id())
                .query((rs, n) -> new Seat(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getBoolean(5)))
                .list()
                .forEach(s -> rows.computeIfAbsent(s.section() + "/" + s.row(), k -> new ArrayList<>()).add(s));

        List<String> best = null;
        double bestScore = Double.MAX_VALUE;
        int rowIndex = 0;
        for (List<Seat> row : rows.values()) {
            double middle = (row.getFirst().number() + row.getLast().number()) / 2.0;
            for (int i = 0; i + count <= row.size(); i++) {
                List<Seat> run = row.subList(i, i + count);
                if (!usable(run, live, accessibleOnly)) {
                    continue;
                }
                double runMiddle = (run.getFirst().number() + run.getLast().number()) / 2.0;
                // a row further back only wins if this one has nothing at all
                double score = rowIndex * 10_000 + Math.abs(runMiddle - middle);
                if (score < bestScore) {
                    bestScore = score;
                    best = run.stream().map(Seat::label).toList();
                }
            }
            rowIndex++;
        }
        if (best == null) {
            throw new ApiException(HttpStatus.CONFLICT, "no_seats_together");
        }
        return best;
    }

    private static boolean usable(List<Seat> run, Map<String, SeatStatus> live, boolean accessibleOnly) {
        for (int i = 0; i < run.size(); i++) {
            Seat s = run.get(i);
            if (live.get(s.label()) != SeatStatus.FREE || (accessibleOnly && !s.accessible())) {
                return false;
            }
            if (i > 0 && s.number() != run.get(i - 1).number() + 1) {
                return false; // a gap (e.g. an aisle or a missing seat) means not together
            }
        }
        return true;
    }
}
