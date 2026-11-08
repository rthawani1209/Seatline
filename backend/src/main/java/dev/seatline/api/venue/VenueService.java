package dev.seatline.api.venue;

import dev.seatline.api.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class VenueService {

    // seat map layout: one unit per seat, a gap between sections
    private static final int SECTION_GAP = 3;

    public record RowSpec(String label, int seats, Set<Integer> accessible, Set<Integer> limitedView) {}

    public record SectionSpec(String name, List<RowSpec> rows) {}

    public record Seat(String label, String row, int number, boolean accessible, boolean limitedView, int x, int y) {}

    public record Section(UUID id, String name, List<Seat> seats) {}

    public record Venue(UUID id, String name, String city, List<Section> sections) {}

    private final JdbcClient db;
    private final JdbcTemplate jdbc;

    public VenueService(JdbcClient db, JdbcTemplate jdbc) {
        this.db = db;
        this.jdbc = jdbc;
    }

    /** Creates a venue and lays out its seats: sections side by side, rows front to back. */
    @Transactional
    public UUID create(String name, String city, List<SectionSpec> sections, UUID createdBy) {
        // seat labels must be unique across the venue, and labels flatten section names
        Set<String> prefixes = new HashSet<>();
        for (SectionSpec spec : sections) {
            if (!prefixes.add(label(spec.name(), "", 0))) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "duplicate_section_name");
            }
            // and two rows called "A" would give two seats the same label
            Set<String> rows = new HashSet<>();
            for (RowSpec row : spec.rows()) {
                if (!rows.add(row.label().trim().toUpperCase())) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "duplicate_row_label");
                }
            }
        }
        UUID venueId = UUID.randomUUID();
        db.sql("insert into venues (id, name, city, created_by) values (?, ?, ?, ?)")
                .params(venueId, name, city, createdBy).update();
        int x0 = 0;
        for (int s = 0; s < sections.size(); s++) {
            SectionSpec spec = sections.get(s);
            UUID sectionId = UUID.randomUUID();
            db.sql("insert into sections (id, venue_id, name, position) values (?, ?, ?, ?)")
                    .params(sectionId, venueId, spec.name(), s).update();
            int widest = 0;
            List<Object[]> seats = new ArrayList<>();
            for (int r = 0; r < spec.rows().size(); r++) {
                RowSpec row = spec.rows().get(r);
                for (int n = 1; n <= row.seats(); n++) {
                    seats.add(new Object[] {UUID.randomUUID(), sectionId, label(spec.name(), row.label(), n),
                            row.label().toUpperCase(), n, row.accessible().contains(n), row.limitedView().contains(n),
                            x0 + n - 1, r});
                }
                widest = Math.max(widest, row.seats());
            }
            jdbc.batchUpdate("""
                    insert into seats (id, section_id, label, row_label, number, accessible, limited_view, x, y)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?)""", seats);
            x0 += widest + SECTION_GAP;
        }
        return venueId;
    }

    /** The seat id the ledger uses: section, row and number, e.g. "FLOOR-B-12". */
    public static String label(String section, String row, int number) {
        return section.toUpperCase().replaceAll("[^A-Z0-9]+", "_") + "-" + row.toUpperCase() + "-" + number;
    }

    public Venue get(UUID id) {
        var venue = db.sql("select name, city from venues where id = ?").param(id)
                .query((rs, n) -> new String[] {rs.getString("name"), rs.getString("city")})
                .optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "no_such_venue"));
        Map<UUID, String> sectionNames = new LinkedHashMap<>();
        db.sql("select id, name from sections where venue_id = ? order by position").param(id)
                .query(rs -> {
                    sectionNames.put(rs.getObject("id", UUID.class), rs.getString("name"));
                });
        List<Section> sections = new ArrayList<>();
        for (var e : sectionNames.entrySet()) {
            List<Seat> seats = db.sql("""
                    select label, row_label, number, accessible, limited_view, x, y
                    from seats where section_id = ? order by y, x""").param(e.getKey())
                    .query((rs, n) -> new Seat(rs.getString("label"), rs.getString("row_label"), rs.getInt("number"),
                            rs.getBoolean("accessible"), rs.getBoolean("limited_view"), rs.getInt("x"), rs.getInt("y")))
                    .list();
            sections.add(new Section(e.getKey(), e.getValue(), seats));
        }
        return new Venue(id, venue[0], venue[1], sections);
    }
}
