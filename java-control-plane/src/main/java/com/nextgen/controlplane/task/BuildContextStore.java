package com.nextgen.controlplane.task;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Server-side staging for Stage N build-context tarballs uploaded by the CLI via
 * {@code UploadBuildContext} — one assembled {@code .tar.gz} per {@code context_id}, on local disk
 * under {@code <dataDir>/build-contexts/}, evicted by TTL rather than kept forever.
 *
 * <p>Deliberately NOT Raft-replicated (see docs/ARCHITECTURE.md's "Consensus & replication" section):
 * a multi-hundred-MB blob has no business going through the write-ahead log. A leader failover mid-
 * upload means the operator re-uploads — an accepted, named limitation for v1.
 *
 * <p>{@link #run()} makes this its own daemon-thread sweeper, matching {@code HeartbeatMonitor}'s own
 * "one Runnable per concern" idiom, rather than a second class wrapping this one.
 */
public final class BuildContextStore implements Runnable {
    private static final Logger LOG = LoggerFactory.getLogger(BuildContextStore.class);

    /** How often a chunk is written before its running SHA-256 digest is updated in memory. */
    private static final int STREAM_BUFFER_SIZE = 64 * 1024;

    private final Path storageDir;
    private final long ttlMillis;
    private final long sweepIntervalMillis;
    private final LongSupplier clock;
    private final ConcurrentHashMap<String, StoredContext> contexts = new ConcurrentHashMap<>();

    public record StoredContext(String contextId, Path path, long sizeBytes, String sha256Hex, long storedAtMillis) { }

    public BuildContextStore(Path dataDir, long ttlMillis, long sweepIntervalMillis) {
        this(dataDir, ttlMillis, sweepIntervalMillis, System::currentTimeMillis);
    }

    public BuildContextStore(Path dataDir, long ttlMillis, long sweepIntervalMillis, LongSupplier clock) {
        this.storageDir = dataDir.resolve("build-contexts");
        this.ttlMillis = ttlMillis;
        this.sweepIntervalMillis = sweepIntervalMillis;
        this.clock = clock;
    }

    /** One in-progress upload — a fresh temp file plus a running digest, both closed by {@link #finish()}. */
    public final class Upload {
        private final String contextId = UUID.randomUUID().toString();
        private final Path tempPath;
        private final OutputStream out;
        private final MessageDigest digest;
        private long bytesWritten = 0;
        private boolean finished = false;

        private Upload() throws IOException {
            Files.createDirectories(storageDir);
            this.tempPath = storageDir.resolve(contextId + ".tar.gz.part");
            this.out = Files.newOutputStream(tempPath, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                this.digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                // SHA-256 is a JDK-mandatory algorithm (JLS-guaranteed) — this can never actually happen.
                throw new IllegalStateException("SHA-256 unavailable", e);
            }
        }

        public String contextId() {
            return contextId;
        }

        public void write(byte[] data) throws IOException {
            out.write(data);
            digest.update(data);
            bytesWritten += data.length;
        }

        /** Closes the temp file, atomically renames it to its final name, and records it in the store. */
        public StoredContext finish() throws IOException {
            if (finished) {
                throw new IllegalStateException("upload " + contextId + " already finished");
            }
            finished = true;
            out.close();
            Path finalPath = storageDir.resolve(contextId + ".tar.gz");
            try {
                Files.move(tempPath, finalPath, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                // Cross-filesystem temp/final dirs (unusual here — both are under the same storageDir)
                // would reject an atomic move; falling back keeps this working rather than failing an
                // otherwise-successful upload over a non-issue.
                Files.move(tempPath, finalPath, StandardCopyOption.REPLACE_EXISTING);
            }
            String sha256Hex = HexFormat.of().formatHex(digest.digest());
            StoredContext stored = new StoredContext(contextId, finalPath, bytesWritten, sha256Hex, clock.getAsLong());
            contexts.put(contextId, stored);
            LOG.info("📦 Stored build context '{}' ({} bytes, sha256={})", contextId, bytesWritten, sha256Hex);
            return stored;
        }

        /** Best-effort cleanup of a partial upload that will never call {@link #finish()} — e.g. the
         * client disconnected mid-stream. Never throws; a leftover temp file is caught by the next
         * process restart at worst, never fatal to the RPC that's failing anyway. */
        public void abort() {
            try {
                out.close();
            } catch (IOException ignored) {
                // Already in a failure path — nothing more useful to do with a close failure here.
            }
            try {
                Files.deleteIfExists(tempPath);
            } catch (IOException ignored) {
                // Same as above.
            }
        }
    }

    public Upload beginUpload() throws IOException {
        return new Upload();
    }

    public Optional<StoredContext> get(String contextId) {
        return Optional.ofNullable(contexts.get(contextId));
    }

    /** Removes every context whose {@link StoredContext#storedAtMillis()} is older than the configured
     * TTL, deleting its file from disk. Called on a fixed interval by {@link #run()}; also callable
     * directly from a test with an injected clock. */
    public void sweepExpired() {
        long now = clock.getAsLong();
        contexts.entrySet().removeIf(entry -> {
            boolean expired = now - entry.getValue().storedAtMillis() > ttlMillis;
            if (expired) {
                try {
                    Files.deleteIfExists(entry.getValue().path());
                } catch (IOException e) {
                    LOG.warn("Could not delete expired build context '{}': {}", entry.getKey(), e.getMessage());
                }
                LOG.debug("🧹 Evicted expired build context '{}'", entry.getKey());
            }
            return expired;
        });
    }

    @Override
    public void run() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(sweepIntervalMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                sweepExpired();
            } catch (RuntimeException e) {
                LOG.warn("Build context sweep failed: {}", e.getMessage(), e);
            }
        }
    }
}
