package com.nextgen.controlplane.raft;

import com.google.protobuf.ByteString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.zip.CRC32;

/**
 * Durable Raft log: persistent {@code currentTerm}/{@code votedFor} ({@code state.meta}) and an
 * append-only entry log ({@code log.wal}), both under {@code directory}. Chosen for debuggability over
 * density — this codebase's own stated value, matching how the JSONL training logs
 * ({@code RiskOutcomeLogger} etc.) are also plain-text-inspectable: {@code log.wal} is tab-separated
 * {@code index / term / base64(command) / CRC32}, one entry per line, so it can be tailed, grepped, and
 * diffed by hand.
 *
 * <p><b>Why term/vote must be fsynced before any RPC leaves the process</b>: a node that grants a vote,
 * crashes, and restarts must never grant a second, conflicting vote in the same term — that is Raft's
 * election safety property. {@link #recordVote} and {@link #setCurrentTerm} both fsync before
 * returning; callers ({@link RaftNode}) must not send an RPC in response to a vote/term change until
 * the corresponding call here has returned.
 *
 * <p>Recovery on open replays {@code log.wal} line by line and <b>truncates at the first line that
 * fails its CRC, fails to parse, or breaks index continuity</b> — a crash mid-write leaves a partial
 * final line, and this discards it rather than trusting it.
 *
 * <p>{@code commitIndex}/{@code lastApplied} are deliberately NOT persisted here — the Raft state
 * machine this log feeds is entirely in-memory and always replays from index 1 on restart (see
 * {@code RaftStateMachine}), which is also why this log never compacts/snapshots: it costs nothing in
 * correctness, only eventual disk growth and restart time (see {@code controlplane_raft_log_entries}).
 *
 * <p>All public methods are synchronized: {@code RaftNode} holds its own lock across log mutations
 * anyway (Raft's invariants are cross-field), so this class's own synchronization exists to make it
 * independently safe to use from tests and tools without relying on that external discipline.
 */
public final class RaftLog implements Closeable {
    private static final Logger LOG = LoggerFactory.getLogger(RaftLog.class);

    private static final String WAL_HEADER = "# nextgen-raft-wal v1\n";
    private static final byte[] WAL_HEADER_BYTES = WAL_HEADER.getBytes(StandardCharsets.US_ASCII);
    private static final String WAL_FILENAME = "log.wal";
    private static final String META_FILENAME = "state.meta";

    private final Path metaPath;
    private final FileChannel walChannel;
    private long walEndOffset;

    // entries.get(i) always has index i+1; entryStartOffsets.get(i) is its line's starting byte offset.
    private final List<LogEntry> entries = new ArrayList<>();
    private final List<Long> entryStartOffsets = new ArrayList<>();

    private long currentTerm;
    private String votedFor = "";

    public RaftLog(Path directory) {
        try {
            Files.createDirectories(directory);
            this.metaPath = directory.resolve(META_FILENAME);
            loadMeta();

            Path walPath = directory.resolve(WAL_FILENAME);
            this.walChannel = FileChannel.open(walPath, StandardOpenOption.CREATE,
                    StandardOpenOption.READ, StandardOpenOption.WRITE);
            replayAndRepair();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to open Raft log at " + directory, e);
        }
    }

    // ── persistent term/vote ──────────────────────────────────────────────

    public synchronized long currentTerm() {
        return currentTerm;
    }

    /** {@code ""} when this replica has not voted in the current term. */
    public synchronized String votedFor() {
        return votedFor;
    }

    public synchronized void setCurrentTerm(long term) {
        this.currentTerm = term;
        this.votedFor = "";
        persistMeta();
    }

    public synchronized void recordVote(long term, String candidateId) {
        this.currentTerm = term;
        this.votedFor = candidateId == null ? "" : candidateId;
        persistMeta();
    }

    // ── log access ──────────────────────────────────────────────────────

    public synchronized long lastIndex() {
        return entries.size();
    }

    public synchronized long lastTerm() {
        return entries.isEmpty() ? 0 : entries.get(entries.size() - 1).term();
    }

    /** 0 for index 0 or an index past the end of the log — never throws on an out-of-range index. */
    public synchronized long termAt(long index) {
        if (index <= 0 || index > entries.size()) {
            return 0;
        }
        return entries.get((int) (index - 1)).term();
    }

    /** The lowest index carrying {@code term}, or 0 if no entry has that term — used for the
     * AppendEntries conflict fast-backup (skip a whole rejected term in one round trip). */
    public synchronized long firstIndexOfTerm(long term) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).term() == term) {
                return i + 1;
            }
        }
        return 0;
    }

    public synchronized Optional<LogEntry> entryAt(long index) {
        if (index <= 0 || index > entries.size()) {
            return Optional.empty();
        }
        return Optional.of(entries.get((int) (index - 1)));
    }

    public synchronized List<LogEntry> entriesFrom(long index, int maxEntries) {
        long start = Math.max(1, index);
        if (start > entries.size()) {
            return List.of();
        }
        int fromIdx = (int) (start - 1);
        int toIdx = Math.min(entries.size(), fromIdx + maxEntries);
        return List.copyOf(entries.subList(fromIdx, toIdx));
    }

    public synchronized int entryCount() {
        return entries.size();
    }

    // ── mutation ────────────────────────────────────────────────────────

    public synchronized LogEntry append(long term, ByteString command) {
        LogEntry entry = appendInMemoryAndWrite(term, command);
        fsyncWal();
        return entry;
    }

    /** One fsync for the whole batch, not one per entry — the throughput-relevant path under load. */
    public synchronized void appendAll(List<LogEntry> newEntries) {
        if (newEntries.isEmpty()) {
            return;
        }
        for (LogEntry e : newEntries) {
            appendInMemoryAndWrite(e.term(), e.command());
        }
        fsyncWal();
    }

    /** Discards every entry from {@code index} onward (inclusive). A no-op if {@code index} is out of range. */
    public synchronized void truncateFrom(long index) {
        if (index <= 0 || index > entries.size()) {
            return;
        }
        int removeFromPos = (int) (index - 1);
        long newOffset = entryStartOffsets.get(removeFromPos);
        try {
            walChannel.truncate(newOffset);
            walChannel.force(true);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to truncate Raft log", e);
        }
        walEndOffset = newOffset;
        while (entries.size() > removeFromPos) {
            entries.remove(entries.size() - 1);
            entryStartOffsets.remove(entryStartOffsets.size() - 1);
        }
    }

    @Override
    public synchronized void close() {
        try {
            walChannel.close();
        } catch (IOException e) {
            LOG.warn("Error closing Raft WAL channel: {}", e.toString());
        }
    }

    // ── internals ───────────────────────────────────────────────────────

    private LogEntry appendInMemoryAndWrite(long term, ByteString command) {
        long index = entries.size() + 1;
        String base64 = command.isEmpty()
                ? "" : Base64.getUrlEncoder().withoutPadding().encodeToString(command.toByteArray());
        String withoutCrc = index + "\t" + term + "\t" + base64;
        long crc = crc32Of(withoutCrc);
        String line = withoutCrc + "\t" + Long.toHexString(crc) + "\n";
        byte[] bytes = line.getBytes(StandardCharsets.US_ASCII);

        long startOffset = walEndOffset;
        writeRaw(bytes);

        LogEntry entry = new LogEntry(index, term, command);
        entries.add(entry);
        entryStartOffsets.add(startOffset);
        return entry;
    }

    private void writeRaw(byte[] bytes) {
        try {
            ByteBuffer buf = ByteBuffer.wrap(bytes);
            while (buf.hasRemaining()) {
                walEndOffset += walChannel.write(buf, walEndOffset);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write to Raft log", e);
        }
    }

    private void fsyncWal() {
        try {
            walChannel.force(true);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to fsync Raft log", e);
        }
    }

    private static long crc32Of(String data) {
        CRC32 crc = new CRC32();
        crc.update(data.getBytes(StandardCharsets.US_ASCII));
        return crc.getValue();
    }

    /**
     * Reads the whole WAL, replays every syntactically- and CRC-valid line in strict index order, and
     * physically truncates the file at the first line that isn't — a torn write from a crash never
     * silently becomes part of the durable log.
     */
    private void replayAndRepair() throws IOException {
        byte[] raw = readAllFromChannel();
        String content = new String(raw, StandardCharsets.US_ASCII);

        long offset = 0;
        long goodEndOffset = 0;
        long expectedIndex = 1;
        boolean sawHeader = false;
        int pos = 0;

        while (pos < content.length()) {
            int newlineIdx = content.indexOf('\n', pos);
            if (newlineIdx < 0) {
                break; // torn final line with no trailing newline — discard
            }
            String line = content.substring(pos, newlineIdx);
            int lineByteLength = (newlineIdx - pos) + 1; // ASCII-only alphabet => 1 byte per char + \n

            if (!sawHeader) {
                if (!line.startsWith("#")) {
                    break; // no valid header at all — treat the file as unrecoverable, reset below
                }
                sawHeader = true;
                offset += lineByteLength;
                goodEndOffset = offset;
                pos = newlineIdx + 1;
                continue;
            }

            long lineStartOffset = offset;
            String[] parts = line.split("\t", -1);
            if (parts.length != 4) {
                break;
            }
            boolean lineOk = true;
            try {
                long index = Long.parseLong(parts[0]);
                long term = Long.parseLong(parts[1]);
                String base64 = parts[2];
                long expectedCrc = crc32Of(parts[0] + "\t" + parts[1] + "\t" + parts[2]);
                long actualCrc = Long.parseLong(parts[3], 16);
                if (expectedCrc != actualCrc || index != expectedIndex) {
                    lineOk = false;
                } else {
                    ByteString command = base64.isEmpty()
                            ? ByteString.EMPTY : ByteString.copyFrom(Base64.getUrlDecoder().decode(base64));
                    entries.add(new LogEntry(index, term, command));
                    entryStartOffsets.add(lineStartOffset);
                    expectedIndex++;
                }
            } catch (RuntimeException e) {
                lineOk = false;
            }
            if (!lineOk) {
                break;
            }

            offset += lineByteLength;
            goodEndOffset = offset;
            pos = newlineIdx + 1;
        }

        if (!sawHeader) {
            LOG.warn("Raft WAL at position 0 has no valid header (empty or fully corrupt file) — "
                    + "starting a fresh log with no entries.");
            entries.clear();
            entryStartOffsets.clear();
            walChannel.truncate(0);
            walEndOffset = 0;
            writeRaw(WAL_HEADER_BYTES);
            walChannel.force(true);
            return;
        }

        if (goodEndOffset != raw.length) {
            LOG.warn("Raft WAL had {} torn/corrupt trailing byte(s) after the last valid entry — "
                    + "truncating to the last known-good position ({}).", raw.length - goodEndOffset, goodEndOffset);
        }
        walChannel.truncate(goodEndOffset);
        walChannel.force(true);
        walEndOffset = goodEndOffset;
    }

    private byte[] readAllFromChannel() throws IOException {
        long size = walChannel.size();
        ByteBuffer buf = ByteBuffer.allocate((int) size);
        walChannel.position(0);
        while (buf.hasRemaining()) {
            if (walChannel.read(buf) < 0) {
                break;
            }
        }
        return buf.array();
    }

    private void loadMeta() {
        if (!Files.exists(metaPath)) {
            currentTerm = 0;
            votedFor = "";
            return;
        }
        try {
            long term = 0;
            String voted = "";
            for (String line : Files.readAllLines(metaPath, StandardCharsets.US_ASCII)) {
                if (line.startsWith("currentTerm=")) {
                    term = Long.parseLong(line.substring("currentTerm=".length()).trim());
                } else if (line.startsWith("votedFor=")) {
                    voted = line.substring("votedFor=".length()).trim();
                }
            }
            currentTerm = term;
            votedFor = voted;
        } catch (IOException | NumberFormatException e) {
            // This must only ever happen on a genuinely fresh/corrupted replica — if this replica had
            // already voted and we silently forget that here, it could cast a second, conflicting vote
            // in the same term. Logged loudly rather than silently defaulted.
            LOG.error("Could not read Raft state.meta at {} ({}) — starting from term 0 with no "
                    + "recorded vote. If this replica has previously participated in an election, this "
                    + "is a real safety concern, not a routine startup message.", metaPath, e.toString());
            currentTerm = 0;
            votedFor = "";
        }
    }

    private void persistMeta() {
        String content = "# nextgen raft persistent state v1\n"
                + "currentTerm=" + currentTerm + "\n"
                + "votedFor=" + votedFor + "\n";
        Path tmp = metaPath.resolveSibling(metaPath.getFileName() + ".tmp");
        try {
            Files.write(tmp, content.getBytes(StandardCharsets.US_ASCII),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE, StandardOpenOption.SYNC);
            try {
                Files.move(tmp, metaPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                LOG.warn("Atomic rename unavailable for {} — falling back to a plain move. This can "
                        + "leave a torn file on a crash mid-move; a known, accepted limitation on "
                        + "filesystems without atomic rename (some Windows configurations).", metaPath);
                Files.move(tmp, metaPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to persist Raft state.meta at " + metaPath, e);
        }
    }
}
