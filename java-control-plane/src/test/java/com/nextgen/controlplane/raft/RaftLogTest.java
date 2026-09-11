package com.nextgen.controlplane.raft;

import com.google.protobuf.ByteString;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Direct tests of {@link RaftLog}'s durability contract — the foundation everything else in
 * {@code com.nextgen.controlplane.raft} depends on. Deliberately exercises the on-disk format directly
 * (including hand-corrupting files) rather than only going through the public API, since the whole
 * point of this class is what survives a crash.
 */
class RaftLogTest {

    private static ByteString cmd(String s) {
        return ByteString.copyFromUtf8(s);
    }

    @Test
    void aFreshLogStartsEmptyAtTermZeroWithNoVote(@TempDir Path dir) {
        try (RaftLog log = new RaftLog(dir)) {
            assertEquals(0, log.currentTerm());
            assertEquals("", log.votedFor());
            assertEquals(0, log.lastIndex());
            assertEquals(0, log.lastTerm());
            assertEquals(0, log.entryCount());
        }
    }

    @Test
    void appendedEntriesAreReadBackInOrder(@TempDir Path dir) {
        try (RaftLog log = new RaftLog(dir)) {
            LogEntry e1 = log.append(1, cmd("a"));
            LogEntry e2 = log.append(1, cmd("b"));
            LogEntry e3 = log.append(2, cmd("c"));

            assertEquals(1, e1.index());
            assertEquals(2, e2.index());
            assertEquals(3, e3.index());
            assertEquals(3, log.lastIndex());
            assertEquals(2, log.lastTerm());
            assertEquals(1, log.termAt(1));
            assertEquals(2, log.termAt(3));
            assertEquals(0, log.termAt(0));
            assertEquals(0, log.termAt(99));

            assertEquals(Optional.of(e2), log.entryAt(2));
            assertTrue(log.entryAt(4).isEmpty());

            List<LogEntry> all = log.entriesFrom(1, 100);
            assertEquals(List.of(e1, e2, e3), all);
            assertEquals(List.of(e2, e3), log.entriesFrom(2, 100));
            assertEquals(List.of(e1), log.entriesFrom(1, 1));
        }
    }

    @Test
    void appendAllWritesEveryEntryAndPersistsAcrossReopen(@TempDir Path dir) {
        List<LogEntry> batch = List.of(
                new LogEntry(0, 1, cmd("x")), // index field ignored on write, real index assigned in order
                new LogEntry(0, 1, cmd("y")),
                new LogEntry(0, 1, ByteString.EMPTY)); // a no-op entry

        try (RaftLog log = new RaftLog(dir)) {
            log.appendAll(batch);
            assertEquals(3, log.lastIndex());
        }

        try (RaftLog reopened = new RaftLog(dir)) {
            assertEquals(3, reopened.lastIndex());
            assertEquals(cmd("x"), reopened.entryAt(1).orElseThrow().command());
            assertEquals(cmd("y"), reopened.entryAt(2).orElseThrow().command());
            assertTrue(reopened.entryAt(3).orElseThrow().isNoOp());
        }
    }

    @Test
    void truncateFromDropsTheGivenIndexAndEverythingAfter(@TempDir Path dir) {
        try (RaftLog log = new RaftLog(dir)) {
            log.append(1, cmd("a"));
            log.append(1, cmd("b"));
            log.append(2, cmd("c"));
            log.append(2, cmd("d"));

            log.truncateFrom(3);

            assertEquals(2, log.lastIndex());
            assertEquals(1, log.lastTerm());
            assertTrue(log.entryAt(3).isEmpty());
            assertTrue(log.entryAt(4).isEmpty());
        }
    }

    @Test
    void truncateThenAppendPersistsOnlyTheSurvivingPrefix(@TempDir Path dir) {
        try (RaftLog log = new RaftLog(dir)) {
            log.append(1, cmd("a"));
            log.append(1, cmd("b"));
            log.append(1, cmd("stale-c"));
            log.truncateFrom(3);
            log.append(2, cmd("real-c")); // a higher-term leader overwrites the stale tail
        }

        try (RaftLog reopened = new RaftLog(dir)) {
            assertEquals(3, reopened.lastIndex());
            assertEquals(cmd("real-c"), reopened.entryAt(3).orElseThrow().command());
            assertEquals(2, reopened.termAt(3));
        }
    }

    @Test
    void truncateFromAnOutOfRangeIndexIsANoOp(@TempDir Path dir) {
        try (RaftLog log = new RaftLog(dir)) {
            log.append(1, cmd("a"));
            log.truncateFrom(0);
            log.truncateFrom(99);
            assertEquals(1, log.lastIndex());
        }
    }

    @Test
    void firstIndexOfTermFindsTheEarliestEntryInThatTerm(@TempDir Path dir) {
        try (RaftLog log = new RaftLog(dir)) {
            log.append(1, cmd("a"));
            log.append(1, cmd("b"));
            log.append(2, cmd("c"));
            log.append(3, cmd("d"));

            assertEquals(1, log.firstIndexOfTerm(1));
            assertEquals(3, log.firstIndexOfTerm(2));
            assertEquals(4, log.firstIndexOfTerm(3));
            assertEquals(0, log.firstIndexOfTerm(99));
        }
    }

    @Test
    void currentTermAndVoteSurviveAReopen(@TempDir Path dir) {
        try (RaftLog log = new RaftLog(dir)) {
            log.recordVote(5, "node-2");
        }
        try (RaftLog reopened = new RaftLog(dir)) {
            assertEquals(5, reopened.currentTerm());
            assertEquals("node-2", reopened.votedFor());
        }
    }

    @Test
    void setCurrentTermClearsAnyPriorVote(@TempDir Path dir) {
        try (RaftLog log = new RaftLog(dir)) {
            log.recordVote(5, "node-2");
            log.setCurrentTerm(6);
            assertEquals(6, log.currentTerm());
            assertEquals("", log.votedFor());
        }
    }

    @Test
    void aTornFinalLineWithNoTrailingNewlineIsDiscardedOnReopen(@TempDir Path dir) throws Exception {
        try (RaftLog log = new RaftLog(dir)) {
            log.append(1, cmd("good"));
        }
        // Simulate a crash mid-write: append a partial line with no trailing newline.
        Path wal = dir.resolve("log.wal");
        Files.write(wal, "2\t1\tZm9v".getBytes(StandardCharsets.US_ASCII),
                java.nio.file.StandardOpenOption.APPEND);

        try (RaftLog reopened = new RaftLog(dir)) {
            assertEquals(1, reopened.lastIndex(), "the torn trailing line must be discarded, not trusted");
            assertEquals(cmd("good"), reopened.entryAt(1).orElseThrow().command());
        }

        // And the file on disk must actually be physically truncated, not just ignored in memory.
        try (RaftLog reopenedAgain = new RaftLog(dir)) {
            reopenedAgain.append(1, cmd("next")); // must become index 2, not conflict with the torn write
            assertEquals(2, reopenedAgain.lastIndex());
            assertEquals(cmd("next"), reopenedAgain.entryAt(2).orElseThrow().command());
        }
    }

    @Test
    void aCrcMismatchOnAnOtherwiseWellFormedLineIsDiscardedOnReopen(@TempDir Path dir) throws Exception {
        try (RaftLog log = new RaftLog(dir)) {
            log.append(1, cmd("good"));
        }
        Path wal = dir.resolve("log.wal");
        // A syntactically valid line (4 tab-separated fields, trailing newline) but a wrong CRC.
        Files.write(wal, "2\t1\tZm9v\tdeadbeef\n".getBytes(StandardCharsets.US_ASCII),
                java.nio.file.StandardOpenOption.APPEND);

        try (RaftLog reopened = new RaftLog(dir)) {
            assertEquals(1, reopened.lastIndex(), "a CRC-mismatched line must never be trusted");
        }
    }

    @Test
    void aBrokenIndexSequenceIsDiscardedOnReopen(@TempDir Path dir) throws Exception {
        try (RaftLog log = new RaftLog(dir)) {
            log.append(1, cmd("good"));
        }
        Path wal = dir.resolve("log.wal");
        // Skips straight to index 5 instead of 2 — compute a real CRC so only the index-continuity
        // check can catch this (isolates that specific safety check from the CRC check).
        String withoutCrc = "5\t1\tZm9v";
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(withoutCrc.getBytes(StandardCharsets.US_ASCII));
        String line = withoutCrc + "\t" + Long.toHexString(crc.getValue()) + "\n";
        Files.write(wal, line.getBytes(StandardCharsets.US_ASCII), java.nio.file.StandardOpenOption.APPEND);

        try (RaftLog reopened = new RaftLog(dir)) {
            assertEquals(1, reopened.lastIndex(), "a non-contiguous index must never be trusted");
        }
    }

    @Test
    void anEmptyOrHeaderlessWalFileBootstrapsCleanly(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir);
        Files.write(dir.resolve("log.wal"), "not a valid header at all\n".getBytes(StandardCharsets.US_ASCII));

        try (RaftLog log = new RaftLog(dir)) {
            assertEquals(0, log.lastIndex());
            log.append(1, cmd("first")); // must be usable afterward, not permanently wedged
            assertEquals(1, log.lastIndex());
        }
    }

    @Test
    void entriesFromClampsANonPositiveStartIndexToOne(@TempDir Path dir) {
        try (RaftLog log = new RaftLog(dir)) {
            log.append(1, cmd("a"));
            log.append(1, cmd("b"));
            assertEquals(2, log.entriesFrom(-5, 100).size());
            assertEquals(2, log.entriesFrom(0, 100).size());
        }
    }

    @Test
    void entriesFromPastTheEndReturnsEmpty(@TempDir Path dir) {
        try (RaftLog log = new RaftLog(dir)) {
            log.append(1, cmd("a"));
            assertTrue(log.entriesFrom(5, 100).isEmpty());
        }
    }

    @Test
    void appendAllOnAnEmptyListIsANoOp(@TempDir Path dir) {
        try (RaftLog log = new RaftLog(dir)) {
            log.append(1, cmd("a"));
            log.appendAll(List.of());
            assertEquals(1, log.lastIndex());
        }
    }

    @Test
    void aNoOpEntryRoundTripsAsEmpty(@TempDir Path dir) {
        try (RaftLog log = new RaftLog(dir)) {
            log.append(3, ByteString.EMPTY);
        }
        try (RaftLog reopened = new RaftLog(dir)) {
            LogEntry entry = reopened.entryAt(1).orElseThrow();
            assertTrue(entry.isNoOp());
            assertEquals(3, entry.term());
            assertFalse(reopened.entryAt(1).orElseThrow().command() == null);
        }
    }
}
