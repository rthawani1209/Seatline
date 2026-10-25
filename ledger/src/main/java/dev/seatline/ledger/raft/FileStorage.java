package dev.seatline.ledger.raft;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32C;

/**
 * Storage on disk, in one directory per node:
 *
 * <ul>
 *   <li>{@code wal} — append-only write-ahead log. Each record is
 *       {@code [length:int][crc32c:int][payload]}; payloads are either an entry or a "truncate
 *       from index" marker. On startup the file is replayed, and a torn record at the end (from a
 *       crash in the middle of a write) is cut off.
 *   <li>{@code hardstate} — current term and vote, replaced atomically (write temp file, fsync,
 *       rename).
 *   <li>{@code snapshot} — the latest snapshot, replaced the same way. After a snapshot the WAL is
 *       rewritten with only the entries that follow it.
 * </ul>
 *
 * <p>The whole log is also kept in memory for reads; snapshots keep it bounded.
 */
public final class FileStorage implements CrashableStorage {

    private static final byte ENTRY = 1;
    private static final byte TRUNCATE = 2;

    private final Path dir;
    private final boolean fsync;
    private final LogBuffer log = new LogBuffer();
    private HardState hard = HardState.INITIAL;
    private HardState syncedHard = HardState.INITIAL;

    private FileChannel wal;
    private final ByteArrayOutputStream pendingBytes = new ByteArrayOutputStream();
    private long syncedWalSize;

    /**
     * @param fsync false skips the actual fsync calls (used by the simulator, which models power
     *     loss itself through {@link #losePower()}); production always passes true
     */
    public FileStorage(Path dir, boolean fsync) {
        this.dir = dir;
        this.fsync = fsync;
        try {
            Files.createDirectories(dir);
            load();
        } catch (IOException e) {
            throw new UncheckedIOException("can't open storage in " + dir, e);
        }
    }

    public FileStorage(Path dir) {
        this(dir, true);
    }

    // ---------------------------------------------------------------- loading

    private void load() throws IOException {
        hard = readHardState();
        syncedHard = hard;
        Snapshot snap = readSnapshot();
        log.reset(snap, List.of());

        Path walPath = dir.resolve("wal");
        wal = FileChannel.open(walPath, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        long goodEnd = replay();
        if (goodEnd < wal.size()) {
            // torn or corrupt tail: everything from here on was never fully written, so drop it
            wal.truncate(goodEnd);
            force(wal);
        }
        wal.position(goodEnd);
        syncedWalSize = goodEnd;
    }

    /** Replays the WAL into memory and returns the offset just past the last intact record. */
    private long replay() throws IOException {
        ByteBuffer header = ByteBuffer.allocate(8);
        long pos = 0;
        long size = wal.size();
        while (pos + 8 <= size) {
            header.clear();
            readFully(header, pos);
            header.flip();
            int length = header.getInt();
            int crc = header.getInt();
            if (length <= 0 || pos + 8 + length > size) {
                break;
            }
            ByteBuffer payload = ByteBuffer.allocate(length);
            readFully(payload, pos + 8);
            if (crc32c(payload.array(), 0, length) != crc) {
                break;
            }
            payload.flip();
            applyRecord(payload);
            pos += 8 + length;
        }
        return pos;
    }

    private void applyRecord(ByteBuffer p) {
        byte type = p.get();
        if (type == ENTRY) {
            long term = p.getLong();
            long index = p.getLong();
            long time = p.getLong();
            byte[] data = new byte[p.getInt()];
            p.get(data);
            if (index < log.firstIndex()) {
                return; // already covered by the snapshot
            }
            if (index <= log.lastIndex()) {
                log.truncateFrom(index);
            }
            log.append(new Entry(term, index, time, data));
        } else if (type == TRUNCATE) {
            long from = p.getLong();
            if (from >= log.firstIndex() && from <= log.lastIndex()) {
                log.truncateFrom(from);
            }
        } else {
            throw new IllegalStateException("unknown WAL record type " + type + " in " + dir);
        }
    }

    private void readFully(ByteBuffer buf, long pos) throws IOException {
        while (buf.hasRemaining()) {
            int n = wal.read(buf, pos + buf.position());
            if (n < 0) {
                throw new IOException("unexpected end of WAL");
            }
        }
    }

    // ---------------------------------------------------------------- Storage

    @Override
    public HardState hardState() {
        return hard;
    }

    @Override
    public void setHardState(HardState state) {
        hard = state;
    }

    @Override
    public long firstIndex() {
        return log.firstIndex();
    }

    @Override
    public long lastIndex() {
        return log.lastIndex();
    }

    @Override
    public long term(long index) {
        return log.term(index);
    }

    @Override
    public Entry entry(long index) {
        return log.entry(index);
    }

    @Override
    public List<Entry> entries(long from, long toInclusive) {
        return log.entries(from, toInclusive);
    }

    @Override
    public void append(List<Entry> entries) {
        for (Entry e : entries) {
            log.append(e);
            writeRecord(encodeEntry(e));
        }
    }

    @Override
    public void truncateFrom(long index) {
        log.truncateFrom(index);
        ByteBuffer b = ByteBuffer.allocate(9);
        b.put(TRUNCATE).putLong(index);
        writeRecord(b.array());
    }

    @Override
    public Snapshot snapshot() {
        return log.snapshot();
    }

    @Override
    public void saveSnapshot(Snapshot snapshot) {
        try {
            sync();
            log.installSnapshot(snapshot);
            writeAtomically("snapshot", encodeSnapshot(log.snapshot()));
            rewriteWal();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void sync() {
        try {
            if (pendingBytes.size() > 0) {
                ByteBuffer buf = ByteBuffer.wrap(pendingBytes.toByteArray());
                while (buf.hasRemaining()) {
                    wal.write(buf);
                }
                pendingBytes.reset();
                force(wal);
                syncedWalSize = wal.position();
            }
            if (!hard.equals(syncedHard)) {
                ByteBuffer b = ByteBuffer.allocate(16);
                b.putLong(hard.term()).putInt(hard.votedFor());
                b.putInt(crc32c(b.array(), 0, 12));
                writeAtomically("hardstate", b.array());
                syncedHard = hard;
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Simulates a power cut: unsynced WAL bytes and hard state are gone, and the node will reload
     * from what's on disk. Only meaningful for tests.
     */
    @Override
    public void losePower() {
        try {
            pendingBytes.reset();
            wal.truncate(syncedWalSize);
            wal.close();
            load();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void close() {
        try {
            sync();
            wal.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---------------------------------------------------------------- encoding

    private void writeRecord(byte[] payload) {
        ByteBuffer header = ByteBuffer.allocate(8);
        header.putInt(payload.length).putInt(crc32c(payload, 0, payload.length));
        pendingBytes.writeBytes(header.array());
        pendingBytes.writeBytes(payload);
    }

    private static byte[] encodeEntry(Entry e) {
        ByteBuffer b = ByteBuffer.allocate(1 + 8 + 8 + 8 + 4 + e.data().length);
        b.put(ENTRY).putLong(e.term()).putLong(e.index()).putLong(e.timeMillis()).putInt(e.data().length).put(e.data());
        return b.array();
    }

    private static byte[] encodeSnapshot(Snapshot s) {
        ByteBuffer b = ByteBuffer.allocate(8 + 8 + 4 + s.data().length + 4);
        b.putLong(s.lastIndex()).putLong(s.lastTerm()).putInt(s.data().length).put(s.data());
        b.putInt(crc32c(b.array(), 0, b.position()));
        return b.array();
    }

    private HardState readHardState() throws IOException {
        Path p = dir.resolve("hardstate");
        if (!Files.exists(p)) {
            return HardState.INITIAL;
        }
        ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(p));
        if (b.remaining() != 16 || crc32c(b.array(), 0, 12) != b.getInt(12)) {
            throw new IOException("corrupt hardstate in " + dir);
        }
        return new HardState(b.getLong(), b.getInt());
    }

    private Snapshot readSnapshot() throws IOException {
        Path p = dir.resolve("snapshot");
        if (!Files.exists(p)) {
            return Snapshot.EMPTY;
        }
        byte[] bytes = Files.readAllBytes(p);
        ByteBuffer b = ByteBuffer.wrap(bytes);
        if (bytes.length < 24 || crc32c(bytes, 0, bytes.length - 4) != b.getInt(bytes.length - 4)) {
            throw new IOException("corrupt snapshot in " + dir);
        }
        long index = b.getLong();
        long term = b.getLong();
        byte[] data = new byte[b.getInt()];
        b.get(data);
        return new Snapshot(index, term, data);
    }

    /** Replaces the WAL with one holding only the entries after the snapshot. */
    private void rewriteWal() throws IOException {
        Path tmp = dir.resolve("wal.tmp");
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(tmp))) {
            for (Entry e : log.all()) {
                byte[] payload = encodeEntry(e);
                out.writeInt(payload.length);
                out.writeInt(crc32c(payload, 0, payload.length));
                out.write(payload);
            }
        }
        try (FileChannel c = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
            force(c);
        }
        wal.close();
        move(tmp, dir.resolve("wal"));
        wal = FileChannel.open(dir.resolve("wal"), StandardOpenOption.READ, StandardOpenOption.WRITE);
        wal.position(wal.size());
        syncedWalSize = wal.size();
    }

    private void writeAtomically(String name, byte[] bytes) throws IOException {
        Path tmp = dir.resolve(name + ".tmp");
        try (FileChannel c = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer buf = ByteBuffer.wrap(bytes);
            while (buf.hasRemaining()) {
                c.write(buf);
            }
            force(c);
        }
        move(tmp, dir.resolve(name));
    }

    private void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
        if (fsync) {
            // make the rename itself durable
            try (FileChannel d = FileChannel.open(dir, StandardOpenOption.READ)) {
                d.force(true);
            } catch (IOException ignored) {
                // some platforms can't open a directory; the rename is still atomic
            }
        }
    }

    private void force(FileChannel c) throws IOException {
        if (fsync) {
            c.force(false);
        }
    }

    private static int crc32c(byte[] b, int off, int len) {
        CRC32C crc = new CRC32C();
        crc.update(b, off, len);
        return (int) crc.getValue();
    }

    /** For tests: the WAL file, so they can tear or corrupt it. */
    Path walPath() {
        return dir.resolve("wal");
    }

    List<Entry> entriesForTest() {
        return new ArrayList<>(log.all());
    }
}
