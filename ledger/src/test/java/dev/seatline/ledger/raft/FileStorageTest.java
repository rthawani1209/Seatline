package dev.seatline.ledger.raft;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileStorageTest {

    @TempDir
    Path dir;

    private static Entry entry(long term, long index) {
        return new Entry(term, index, 1000 + index, ("cmd-" + index).getBytes(StandardCharsets.UTF_8));
    }

    private static List<Entry> entries(long term, long from, long to) {
        List<Entry> out = new ArrayList<>();
        for (long i = from; i <= to; i++) {
            out.add(entry(term, i));
        }
        return out;
    }

    @Test
    void entriesAndHardStateSurviveReopen() {
        FileStorage s = new FileStorage(dir);
        s.append(entries(1, 1, 10));
        s.setHardState(new HardState(3, 2));
        s.sync();
        s.close();

        FileStorage reopened = new FileStorage(dir);
        assertThat(reopened.lastIndex()).isEqualTo(10);
        assertThat(reopened.entry(7)).usingRecursiveComparison().isEqualTo(entry(1, 7));
        assertThat(reopened.hardState()).isEqualTo(new HardState(3, 2));
    }

    @Test
    void truncationIsReplayed() {
        FileStorage s = new FileStorage(dir);
        s.append(entries(1, 1, 10));
        s.truncateFrom(6);
        s.append(entries(2, 6, 8));
        s.sync();
        s.close();

        FileStorage reopened = new FileStorage(dir);
        assertThat(reopened.lastIndex()).isEqualTo(8);
        assertThat(reopened.term(5)).isEqualTo(1);
        assertThat(reopened.term(6)).isEqualTo(2);
    }

    @Test
    void unsyncedWritesAreLostOnPowerCut() {
        FileStorage s = new FileStorage(dir, false);
        s.append(entries(1, 1, 5));
        s.setHardState(new HardState(1, 1));
        s.sync();
        s.append(entries(1, 6, 9));
        s.setHardState(new HardState(2, 3));

        s.losePower();

        assertThat(s.lastIndex()).isEqualTo(5);
        assertThat(s.hardState()).isEqualTo(new HardState(1, 1));
    }

    @Test
    void tornTailRecordIsDropped() throws IOException {
        FileStorage s = new FileStorage(dir);
        s.append(entries(1, 1, 20));
        s.sync();
        s.close();

        // cut the file in the middle of the last record, like a crash mid-write
        Path wal = dir.resolve("wal");
        try (RandomAccessFile f = new RandomAccessFile(wal.toFile(), "rw")) {
            f.setLength(f.length() - 7);
        }

        FileStorage reopened = new FileStorage(dir);
        assertThat(reopened.lastIndex()).isEqualTo(19);
        // and the file was repaired, so new appends land after entry 19
        reopened.append(List.of(entry(2, 20)));
        reopened.sync();
        reopened.close();
        assertThat(new FileStorage(dir).term(20)).isEqualTo(2);
    }

    @Test
    void corruptTailRecordIsDropped() throws IOException {
        FileStorage s = new FileStorage(dir);
        s.append(entries(1, 1, 3));
        s.sync();
        s.close();

        Path wal = dir.resolve("wal");
        byte[] bytes = Files.readAllBytes(wal);
        bytes[bytes.length - 2] ^= 0x5A; // flip bits inside the last entry's data
        Files.write(wal, bytes);

        assertThat(new FileStorage(dir).lastIndex()).isEqualTo(2);
    }

    @Test
    void snapshotCompactsLogAndSurvivesReopen() throws IOException {
        FileStorage s = new FileStorage(dir);
        s.append(entries(1, 1, 100));
        s.sync();
        long walBefore = Files.size(dir.resolve("wal"));

        s.saveSnapshot(new Snapshot(90, 1, "state@90".getBytes(StandardCharsets.UTF_8)));
        assertThat(s.firstIndex()).isEqualTo(91);
        assertThat(s.term(90)).isEqualTo(1);
        assertThat(Files.size(dir.resolve("wal"))).isLessThan(walBefore / 5);
        s.append(entries(2, 101, 105));
        s.sync();
        s.close();

        FileStorage reopened = new FileStorage(dir);
        assertThat(reopened.snapshot().lastIndex()).isEqualTo(90);
        assertThat(new String(reopened.snapshot().data(), StandardCharsets.UTF_8)).isEqualTo("state@90");
        assertThat(reopened.firstIndex()).isEqualTo(91);
        assertThat(reopened.lastIndex()).isEqualTo(105);
        assertThatThrownBy(() -> reopened.entry(50)).isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    void snapshotThatDisagreesWithLogReplacesIt() {
        FileStorage s = new FileStorage(dir);
        s.append(entries(1, 1, 10));
        // leader's snapshot ends at index 8 with a term our log doesn't have there
        s.saveSnapshot(new Snapshot(8, 3, new byte[] {1}));
        assertThat(s.firstIndex()).isEqualTo(9);
        assertThat(s.lastIndex()).isEqualTo(8);
    }

    @Test
    void corruptHardStateIsRefused() throws IOException {
        FileStorage s = new FileStorage(dir);
        s.setHardState(new HardState(5, 1));
        s.sync();
        s.close();
        byte[] bytes = Files.readAllBytes(dir.resolve("hardstate"));
        bytes[3] ^= 1;
        Files.write(dir.resolve("hardstate"), bytes);
        // better to refuse to start than to forget a vote and vote twice in one term
        assertThatThrownBy(() -> new FileStorage(dir)).rootCause().hasMessageContaining("corrupt hardstate");
    }
}
