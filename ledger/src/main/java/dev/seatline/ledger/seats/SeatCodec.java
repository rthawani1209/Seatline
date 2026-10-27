package dev.seatline.ledger.seats;

import dev.seatline.ledger.seats.SeatCommand.Confirm;
import dev.seatline.ledger.seats.SeatCommand.CreateEvent;
import dev.seatline.ledger.seats.SeatCommand.ExpireDue;
import dev.seatline.ledger.seats.SeatCommand.Hold;
import dev.seatline.ledger.seats.SeatCommand.Release;
import dev.seatline.ledger.seats.SeatCommand.Return;
import dev.seatline.ledger.seats.SeatCommand.Transfer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/** Binary encoding of requests and results, as stored in the Raft log and sent to clients. */
public final class SeatCodec {

    private SeatCodec() {}

    public static byte[] encode(SeatRequest r) {
        return write(out -> {
            out.writeLong(r.clientId());
            out.writeLong(r.seq());
            switch (r.command()) {
                case CreateEvent c -> {
                    out.writeByte(1);
                    out.writeUTF(c.eventId());
                    writeList(out, c.seatIds());
                    out.writeInt(c.limitPerFan());
                }
                case Hold h -> {
                    out.writeByte(2);
                    out.writeUTF(h.eventId());
                    writeList(out, h.seatIds());
                    out.writeUTF(h.fanId());
                    out.writeUTF(h.holdId());
                    out.writeLong(h.ttlMillis());
                }
                case Confirm c -> {
                    out.writeByte(3);
                    out.writeUTF(c.holdId());
                    out.writeUTF(c.paymentRef());
                }
                case Release rel -> {
                    out.writeByte(4);
                    out.writeUTF(rel.holdId());
                }
                case ExpireDue e -> out.writeByte(5);
                case Transfer t -> {
                    out.writeByte(6);
                    out.writeUTF(t.ticketId());
                    out.writeUTF(t.fromFan());
                    out.writeUTF(t.toFan());
                }
                case Return ret -> {
                    out.writeByte(7);
                    out.writeUTF(ret.ticketId());
                    out.writeUTF(ret.fanId());
                }
            }
        });
    }

    public static SeatRequest decodeRequest(byte[] bytes) {
        return read(bytes, in -> {
            long clientId = in.readLong();
            long seq = in.readLong();
            byte type = in.readByte();
            SeatCommand c = switch (type) {
                case 1 -> new CreateEvent(in.readUTF(), readList(in), in.readInt());
                case 2 -> new Hold(in.readUTF(), readList(in), in.readUTF(), in.readUTF(), in.readLong());
                case 3 -> new Confirm(in.readUTF(), in.readUTF());
                case 4 -> new Release(in.readUTF());
                case 5 -> new ExpireDue();
                case 6 -> new Transfer(in.readUTF(), in.readUTF(), in.readUTF());
                case 7 -> new Return(in.readUTF(), in.readUTF());
                default -> throw new IllegalArgumentException("unknown seat command " + type);
            };
            if (in.available() > 0) {
                throw new IllegalArgumentException("trailing bytes after seat command");
            }
            return new SeatRequest(clientId, seq, c);
        });
    }

    public static byte[] encode(SeatResult r) {
        return write(out -> {
            out.writeByte(r.outcome().ordinal());
            out.writeLong(r.expiresAt());
            writeList(out, r.ids());
        });
    }

    public static SeatResult decodeResult(byte[] bytes) {
        return read(bytes, in -> new SeatResult(SeatResult.Outcome.values()[in.readByte()], in.readLong(), readList(in)));
    }

    // ---------------------------------------------------------------- helpers

    interface Writer {
        void write(DataOutputStream out) throws IOException;
    }

    interface Reader<T> {
        T read(DataInputStream in) throws IOException;
    }

    static byte[] write(Writer w) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            w.write(out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    static <T> T read(byte[] bytes, Reader<T> r) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            return r.read(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static void writeList(DataOutputStream out, List<String> list) throws IOException {
        out.writeInt(list.size());
        for (String s : list) {
            out.writeUTF(s);
        }
    }

    static List<String> readList(DataInputStream in) throws IOException {
        int n = in.readInt();
        // every string takes at least 2 bytes, so a bigger count can only come from garbage
        if (n < 0 || n > in.available() / 2) {
            throw new IOException("bad list length " + n);
        }
        List<String> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            list.add(in.readUTF());
        }
        return List.copyOf(list);
    }
}
