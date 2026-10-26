package dev.seatline.ledger.seats;

import dev.seatline.ledger.raft.Entry;
import dev.seatline.ledger.raft.StateMachine;
import dev.seatline.ledger.seats.SeatCommand.Confirm;
import dev.seatline.ledger.seats.SeatCommand.CreateEvent;
import dev.seatline.ledger.seats.SeatCommand.ExpireDue;
import dev.seatline.ledger.seats.SeatCommand.Hold;
import dev.seatline.ledger.seats.SeatCommand.Release;
import dev.seatline.ledger.seats.SeatCommand.Return;
import dev.seatline.ledger.seats.SeatCommand.Transfer;
import dev.seatline.ledger.seats.SeatResult.Outcome;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The seat ledger: who holds and who owns every seat. This is the state machine every Raft node
 * runs, so it must be deterministic. Time comes only from the leader's timestamp on each log
 * entry, never from a clock, and every map iterates in a fixed order.
 *
 * <p>A seat is in exactly one state at a time (free, held by one hold, or sold as one ticket), which
 * is what makes double-selling impossible: the only way to sell a seat is to move it from "held by
 * your hold" to "sold", and only one hold can have it.
 */
public final class SeatLedger implements StateMachine {

    /** Keep answers for this many clients for retry deduplication; the oldest are forgotten first. */
    static final int MAX_CLIENTS = 100_000;

    private final TreeMap<String, EventSeats> events = new TreeMap<>();
    private final TreeMap<String, HoldRecord> holds = new TreeMap<>();
    private final TreeMap<String, Ticket> tickets = new TreeMap<>();
    private final TreeSet<Expiry> expiries = new TreeSet<>();
    private final LinkedHashMap<Long, Answer> answers = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Answer> eldest) {
            return size() > MAX_CLIENTS;
        }
    };

    private static final class EventSeats {
        final int limitPerFan;
        /** seat id -> "" when free, "H:holdId" when held, "T:ticketId" when sold */
        final TreeMap<String, String> seats = new TreeMap<>();
        final TreeMap<String, TreeSet<String>> holdsByFan = new TreeMap<>();
        final TreeMap<String, Integer> ticketsByFan = new TreeMap<>();

        EventSeats(int limitPerFan) {
            this.limitPerFan = limitPerFan;
        }
    }

    record HoldRecord(String holdId, String eventId, String fanId, List<String> seats, long expiresAt) {}

    public record Ticket(String ticketId, String eventId, String seatId, String ownerFan, int version, String paymentRef) {}

    private record Expiry(long at, String holdId) implements Comparable<Expiry> {
        @Override
        public int compareTo(Expiry o) {
            return at != o.at ? Long.compare(at, o.at) : holdId.compareTo(o.holdId);
        }
    }

    private record Answer(long seq, byte[] result) {}

    // ---------------------------------------------------------------- applying commands

    @Override
    public byte[] apply(Entry entry) {
        SeatRequest req = SeatCodec.decodeRequest(entry.data());
        if (req.clientId() != 0) {
            Answer previous = answers.get(req.clientId());
            if (previous != null && req.seq() == previous.seq()) {
                return previous.result(); // a retry: same answer, no second effect
            }
            if (previous != null && req.seq() < previous.seq()) {
                return SeatCodec.encode(SeatResult.of(Outcome.STALE_REQUEST));
            }
        }
        byte[] result = SeatCodec.encode(execute(req.command(), entry.timeMillis()));
        if (req.clientId() != 0) {
            answers.put(req.clientId(), new Answer(req.seq(), result));
        }
        return result;
    }

    /** Runs one command against the state. {@code now} is the leader's timestamp for the entry. */
    public SeatResult execute(SeatCommand command, long now) {
        return switch (command) {
            case CreateEvent c -> createEvent(c);
            case Hold h -> hold(h, now);
            case Confirm c -> confirm(c, now);
            case Release r -> release(r);
            case ExpireDue e -> expireDue(now);
            case Transfer t -> transfer(t);
            case Return r -> returnTicket(r);
        };
    }

    private SeatResult createEvent(CreateEvent c) {
        if (events.containsKey(c.eventId())) {
            return SeatResult.of(Outcome.ALREADY_EXISTS);
        }
        if (c.seatIds().isEmpty() || c.limitPerFan() <= 0 || new HashSet<>(c.seatIds()).size() != c.seatIds().size()) {
            return SeatResult.of(Outcome.BAD_REQUEST);
        }
        EventSeats ev = new EventSeats(c.limitPerFan());
        for (String seat : c.seatIds()) {
            ev.seats.put(seat, "");
        }
        events.put(c.eventId(), ev);
        return SeatResult.ok(List.of());
    }

    private SeatResult hold(Hold h, long now) {
        EventSeats ev = events.get(h.eventId());
        if (ev == null) {
            return SeatResult.of(Outcome.NO_SUCH_EVENT);
        }
        if (h.seatIds().isEmpty() || h.ttlMillis() <= 0 || new HashSet<>(h.seatIds()).size() != h.seatIds().size()) {
            return SeatResult.of(Outcome.BAD_REQUEST);
        }
        if (holds.containsKey(h.holdId())) {
            return SeatResult.of(Outcome.ALREADY_EXISTS);
        }
        // check everything first so a hold is all-or-nothing
        List<String> taken = new ArrayList<>();
        for (String seat : h.seatIds()) {
            String state = ev.seats.get(seat);
            if (state == null) {
                return SeatResult.of(Outcome.NO_SUCH_SEAT);
            }
            if (state.startsWith("H:")) {
                HoldRecord other = holds.get(state.substring(2));
                if (now > other.expiresAt()) {
                    expire(other); // its time is up; the seat is fair game
                    state = "";
                }
            }
            if (!state.isEmpty()) {
                taken.add(seat);
            }
        }
        if (!taken.isEmpty()) {
            return new SeatResult(Outcome.SEATS_TAKEN, 0, List.copyOf(taken));
        }
        if (seatsFor(ev, h.fanId(), now) + h.seatIds().size() > ev.limitPerFan) {
            return SeatResult.of(Outcome.LIMIT_REACHED);
        }

        long expiresAt = now + h.ttlMillis();
        HoldRecord rec = new HoldRecord(h.holdId(), h.eventId(), h.fanId(), List.copyOf(h.seatIds()), expiresAt);
        holds.put(rec.holdId(), rec);
        expiries.add(new Expiry(expiresAt, rec.holdId()));
        ev.holdsByFan.computeIfAbsent(h.fanId(), k -> new TreeSet<>()).add(rec.holdId());
        for (String seat : rec.seats()) {
            ev.seats.put(seat, "H:" + rec.holdId());
        }
        return new SeatResult(Outcome.OK, expiresAt, rec.seats());
    }

    /** Seats a fan has right now in an event: live holds plus tickets. Cleans up their expired holds. */
    private int seatsFor(EventSeats ev, String fanId, long now) {
        int count = ev.ticketsByFan.getOrDefault(fanId, 0);
        TreeSet<String> fanHolds = ev.holdsByFan.get(fanId);
        if (fanHolds != null) {
            for (String holdId : List.copyOf(fanHolds)) {
                HoldRecord rec = holds.get(holdId);
                if (now > rec.expiresAt()) {
                    expire(rec);
                } else {
                    count += rec.seats().size();
                }
            }
        }
        return count;
    }

    private SeatResult confirm(Confirm c, long now) {
        HoldRecord rec = holds.get(c.holdId());
        if (rec == null) {
            return SeatResult.of(Outcome.NO_SUCH_HOLD);
        }
        if (now > rec.expiresAt()) {
            // Too late: payment went through after the hold ran out. The API refunds the fan.
            expire(rec);
            return SeatResult.of(Outcome.HOLD_EXPIRED);
        }
        EventSeats ev = events.get(rec.eventId());
        removeHold(rec);
        List<String> issued = new ArrayList<>();
        for (String seat : rec.seats()) {
            String ticketId = "T-" + rec.holdId() + "-" + seat;
            tickets.put(ticketId, new Ticket(ticketId, rec.eventId(), seat, rec.fanId(), 1, c.paymentRef()));
            ev.seats.put(seat, "T:" + ticketId);
            issued.add(ticketId);
        }
        ev.ticketsByFan.merge(rec.fanId(), issued.size(), Integer::sum);
        return SeatResult.ok(List.copyOf(issued));
    }

    private SeatResult release(Release r) {
        HoldRecord rec = holds.get(r.holdId());
        if (rec == null) {
            return SeatResult.of(Outcome.NO_SUCH_HOLD);
        }
        expire(rec);
        return SeatResult.ok(rec.seats());
    }

    private SeatResult expireDue(long now) {
        List<String> expired = new ArrayList<>();
        for (Expiry e : expiries) {
            if (e.at() >= now) {
                break;
            }
            expired.add(e.holdId());
        }
        for (String holdId : expired) {
            expire(holds.get(holdId));
        }
        return SeatResult.ok(List.copyOf(expired));
    }

    private SeatResult transfer(Transfer t) {
        Ticket ticket = tickets.get(t.ticketId());
        if (ticket == null) {
            return SeatResult.of(Outcome.NO_SUCH_TICKET);
        }
        if (!ticket.ownerFan().equals(t.fromFan())) {
            return SeatResult.of(Outcome.NOT_OWNER);
        }
        if (t.toFan().isBlank() || t.toFan().equals(t.fromFan())) {
            return SeatResult.of(Outcome.BAD_REQUEST);
        }
        EventSeats ev = events.get(ticket.eventId());
        if (ev.ticketsByFan.getOrDefault(t.toFan(), 0) + 1 > ev.limitPerFan) {
            return SeatResult.of(Outcome.LIMIT_REACHED);
        }
        tickets.put(ticket.ticketId(), new Ticket(ticket.ticketId(), ticket.eventId(), ticket.seatId(), t.toFan(),
                ticket.version() + 1, ticket.paymentRef()));
        decrement(ev.ticketsByFan, t.fromFan());
        ev.ticketsByFan.merge(t.toFan(), 1, Integer::sum);
        return SeatResult.ok(List.of(ticket.ticketId()));
    }

    private SeatResult returnTicket(Return r) {
        Ticket ticket = tickets.get(r.ticketId());
        if (ticket == null) {
            return SeatResult.of(Outcome.NO_SUCH_TICKET);
        }
        if (!ticket.ownerFan().equals(r.fanId())) {
            return SeatResult.of(Outcome.NOT_OWNER);
        }
        EventSeats ev = events.get(ticket.eventId());
        tickets.remove(ticket.ticketId());
        ev.seats.put(ticket.seatId(), "");
        decrement(ev.ticketsByFan, ticket.ownerFan());
        return SeatResult.ok(List.of(ticket.seatId()));
    }

    private void expire(HoldRecord rec) {
        EventSeats ev = events.get(rec.eventId());
        removeHold(rec);
        for (String seat : rec.seats()) {
            ev.seats.put(seat, "");
        }
    }

    private void removeHold(HoldRecord rec) {
        holds.remove(rec.holdId());
        expiries.remove(new Expiry(rec.expiresAt(), rec.holdId()));
        EventSeats ev = events.get(rec.eventId());
        TreeSet<String> fanHolds = ev.holdsByFan.get(rec.fanId());
        fanHolds.remove(rec.holdId());
        if (fanHolds.isEmpty()) {
            ev.holdsByFan.remove(rec.fanId());
        }
    }

    private static void decrement(TreeMap<String, Integer> counts, String key) {
        int n = counts.get(key) - 1;
        if (n == 0) {
            counts.remove(key);
        } else {
            counts.put(key, n);
        }
    }

    // ---------------------------------------------------------------- reads

    public enum SeatStatus { FREE, HELD, SOLD }

    public record SeatView(String seatId, SeatStatus status, String holdId, String ticketId) {}

    /**
     * Every seat of an event in seat-id order, as of {@code now}. Holds past their expiry show as
     * free even before the sweeper removes them. Returns null for an unknown event.
     */
    public List<SeatView> seatMap(String eventId, long now) {
        EventSeats ev = events.get(eventId);
        if (ev == null) {
            return null;
        }
        List<SeatView> out = new ArrayList<>(ev.seats.size());
        for (Map.Entry<String, String> e : ev.seats.entrySet()) {
            String state = e.getValue();
            if (state.isEmpty()) {
                out.add(new SeatView(e.getKey(), SeatStatus.FREE, null, null));
            } else if (state.startsWith("H:")) {
                String holdId = state.substring(2);
                boolean live = now <= holds.get(holdId).expiresAt();
                out.add(live ? new SeatView(e.getKey(), SeatStatus.HELD, holdId, null)
                        : new SeatView(e.getKey(), SeatStatus.FREE, null, null));
            } else {
                out.add(new SeatView(e.getKey(), SeatStatus.SOLD, null, state.substring(2)));
            }
        }
        return out;
    }

    public HoldRecord hold(String holdId) {
        return holds.get(holdId);
    }

    public Ticket ticket(String ticketId) {
        return tickets.get(ticketId);
    }

    /**
     * Checks the ledger's internal bookkeeping agrees with itself. Throws if not. Used by tests after
     * every step; cheap enough to run on a few thousand seats.
     */
    public void checkInvariants() {
        Set<String> seenTickets = new HashSet<>();
        int heldSeats = 0;
        for (Map.Entry<String, EventSeats> e : events.entrySet()) {
            String eventId = e.getKey();
            EventSeats ev = e.getValue();
            TreeMap<String, Integer> fanTickets = new TreeMap<>();
            for (Map.Entry<String, String> s : ev.seats.entrySet()) {
                String state = s.getValue();
                if (state.startsWith("H:")) {
                    HoldRecord rec = holds.get(state.substring(2));
                    require(rec != null && rec.eventId().equals(eventId) && rec.seats().contains(s.getKey()),
                            "seat " + s.getKey() + " points at a missing or wrong hold");
                    heldSeats++;
                } else if (state.startsWith("T:")) {
                    Ticket t = tickets.get(state.substring(2));
                    require(t != null && t.seatId().equals(s.getKey()) && t.eventId().equals(eventId),
                            "seat " + s.getKey() + " points at a missing or wrong ticket");
                    require(seenTickets.add(t.ticketId()), "ticket " + t.ticketId() + " covers two seats");
                    fanTickets.merge(t.ownerFan(), 1, Integer::sum);
                } else {
                    require(state.isEmpty(), "bad seat state " + state);
                }
            }
            require(fanTickets.equals(ev.ticketsByFan), "per-fan ticket counts are off for " + eventId);
        }
        require(seenTickets.size() == tickets.size(), "a ticket exists without a seat pointing at it");
        int seatsInHolds = 0;
        for (HoldRecord rec : holds.values()) {
            seatsInHolds += rec.seats().size();
            require(expiries.contains(new Expiry(rec.expiresAt(), rec.holdId())), "hold " + rec.holdId() + " has no expiry entry");
            require(events.get(rec.eventId()).holdsByFan.getOrDefault(rec.fanId(), new TreeSet<>()).contains(rec.holdId()),
                    "hold " + rec.holdId() + " missing from its fan's list");
        }
        require(seatsInHolds == heldSeats, "a hold lists a seat that isn't held by it");
        require(expiries.size() == holds.size(), "expiry index out of sync");
    }

    private static void require(boolean ok, String message) {
        if (!ok) {
            throw new IllegalStateException("seat ledger invariant broken: " + message);
        }
    }

    // ---------------------------------------------------------------- snapshots

    @Override
    public byte[] snapshot() {
        return SeatCodec.write(out -> {
            out.writeInt(events.size());
            for (Map.Entry<String, EventSeats> e : events.entrySet()) {
                out.writeUTF(e.getKey());
                out.writeInt(e.getValue().limitPerFan);
                out.writeInt(e.getValue().seats.size());
                for (Map.Entry<String, String> s : e.getValue().seats.entrySet()) {
                    out.writeUTF(s.getKey());
                    out.writeUTF(s.getValue());
                }
            }
            out.writeInt(holds.size());
            for (HoldRecord h : holds.values()) {
                out.writeUTF(h.holdId());
                out.writeUTF(h.eventId());
                out.writeUTF(h.fanId());
                SeatCodec.writeList(out, h.seats());
                out.writeLong(h.expiresAt());
            }
            out.writeInt(tickets.size());
            for (Ticket t : tickets.values()) {
                writeTicket(out, t);
            }
            // in least-recently-used order, so restoring reproduces the same eviction order
            out.writeInt(answers.size());
            for (Map.Entry<Long, Answer> a : answers.entrySet()) {
                out.writeLong(a.getKey());
                out.writeLong(a.getValue().seq());
                out.writeInt(a.getValue().result().length);
                out.write(a.getValue().result());
            }
        });
    }

    @Override
    public void restore(byte[] snapshot) {
        events.clear();
        holds.clear();
        tickets.clear();
        expiries.clear();
        answers.clear();
        SeatCodec.read(snapshot, in -> {
            int nEvents = in.readInt();
            for (int i = 0; i < nEvents; i++) {
                String id = in.readUTF();
                EventSeats ev = new EventSeats(in.readInt());
                int nSeats = in.readInt();
                for (int s = 0; s < nSeats; s++) {
                    ev.seats.put(in.readUTF(), in.readUTF());
                }
                events.put(id, ev);
            }
            int nHolds = in.readInt();
            for (int i = 0; i < nHolds; i++) {
                HoldRecord h = new HoldRecord(in.readUTF(), in.readUTF(), in.readUTF(), SeatCodec.readList(in), in.readLong());
                holds.put(h.holdId(), h);
                expiries.add(new Expiry(h.expiresAt(), h.holdId()));
                events.get(h.eventId()).holdsByFan.computeIfAbsent(h.fanId(), k -> new TreeSet<>()).add(h.holdId());
            }
            int nTickets = in.readInt();
            for (int i = 0; i < nTickets; i++) {
                Ticket t = readTicket(in);
                tickets.put(t.ticketId(), t);
                events.get(t.eventId()).ticketsByFan.merge(t.ownerFan(), 1, Integer::sum);
            }
            int nAnswers = in.readInt();
            for (int i = 0; i < nAnswers; i++) {
                long client = in.readLong();
                long seq = in.readLong();
                byte[] result = new byte[in.readInt()];
                in.readFully(result);
                answers.put(client, new Answer(seq, result));
            }
            return null;
        });
    }

    private static void writeTicket(DataOutputStream out, Ticket t) throws IOException {
        out.writeUTF(t.ticketId());
        out.writeUTF(t.eventId());
        out.writeUTF(t.seatId());
        out.writeUTF(t.ownerFan());
        out.writeInt(t.version());
        out.writeUTF(t.paymentRef());
    }

    private static Ticket readTicket(DataInputStream in) throws IOException {
        return new Ticket(in.readUTF(), in.readUTF(), in.readUTF(), in.readUTF(), in.readInt(), in.readUTF());
    }
}
