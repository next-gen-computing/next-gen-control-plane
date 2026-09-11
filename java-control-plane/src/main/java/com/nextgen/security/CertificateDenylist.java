package com.nextgen.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Serial-number denylist for revoked certificates, consulted on <b>every RPC</b>.
 *
 * <h2>Why not a CRL</h2>
 *
 * A CRL is consulted during the TLS handshake. Agents hold long-lived channels, so revoking a
 * certificate would do nothing to a connection that is already open — the compromised node would keep
 * working until it happened to reconnect. A per-RPC check kills the <i>next RPC</i> on an existing
 * connection, which is the property that actually matters here. {@code revokingCertBreaksNextRpc} in
 * the test suite is the proof.
 *
 * <p>The file is polled rather than watched: polling is portable, deterministic, and testable by
 * advancing an injected nanosecond clock. {@code WatchService} on Windows has native-watcher latency
 * quirks that would force sleeps into every test.
 */
public final class CertificateDenylist {
    private static final Logger LOG = LoggerFactory.getLogger(CertificateDenylist.class);

    private final Path file;
    private final long reloadIntervalNanos;
    private final LongSupplier nanoClock;

    /** Swapped wholesale on reload, so readers never observe a partially-loaded set. */
    private volatile Set<BigInteger> revoked = Set.of();

    private volatile long lastReloadNanos;
    private volatile long lastModifiedMillis = -1;

    public CertificateDenylist(Path file, Duration reloadInterval, LongSupplier nanoClock) {
        this.file = file;
        this.reloadIntervalNanos = reloadInterval.toNanos();
        this.nanoClock = nanoClock;
        this.lastReloadNanos = nanoClock.getAsLong() - reloadIntervalNanos - 1;
        maybeReload();
    }

    /** True when this serial has been revoked. O(1) after an occasional reload check. */
    public boolean isRevoked(BigInteger serial) {
        maybeReload();
        return serial != null && revoked.contains(serial);
    }

    /**
     * Revokes a serial.
     *
     * <p>Updates the in-memory set immediately as well as appending to the file, so revocation takes
     * effect on the very next RPC rather than after the poll interval.
     */
    public synchronized void revoke(BigInteger serial, Instant certNotAfter, String reason) {
        if (serial == null) {
            return;
        }
        Set<BigInteger> updated = new HashSet<>(revoked);
        updated.add(serial);
        revoked = Set.copyOf(updated);

        try {
            String line = String.format(Locale.ROOT, "%s %d %d %s%n",
                    serial.toString(16),
                    certNotAfter == null ? 0L : certNotAfter.toEpochMilli(),
                    System.currentTimeMillis(),
                    reason == null ? "unspecified" : reason.replace('\n', ' '));
            Files.writeString(file, line,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.SYNC);
            lastModifiedMillis = Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            // The in-memory revocation still stands for this process; only durability is lost.
            LOG.error("Revoked serial {} in memory but could not persist it to {}",
                    serial.toString(16), file, e);
        }
        LOG.warn("Certificate serial {} revoked ({})", serial.toString(16), reason);
    }

    public int size() {
        maybeReload();
        return revoked.size();
    }

    /**
     * Reloads from disk if the poll interval has elapsed and the file has changed.
     *
     * <p>Package-private so tests can drive it deterministically after advancing the clock.
     */
    void maybeReload() {
        long now = nanoClock.getAsLong();
        if (now - lastReloadNanos < reloadIntervalNanos) {
            return;
        }
        lastReloadNanos = now;

        try {
            if (!Files.exists(file)) {
                // A missing file means nothing is revoked. That is not an error.
                revoked = Set.of();
                lastModifiedMillis = -1;
                return;
            }
            long modified = Files.getLastModifiedTime(file).toMillis();
            if (modified == lastModifiedMillis) {
                return;
            }
            lastModifiedMillis = modified;

            Set<BigInteger> parsed = new HashSet<>();
            List<String> lines = Files.readAllLines(file);
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                String serialToken = trimmed.split("\\s+")[0];
                try {
                    parsed.add(new BigInteger(serialToken, 16));
                } catch (NumberFormatException e) {
                    // Skip, never fatal: one bad line must not disable revocation entirely.
                    LOG.warn("Skipping malformed denylist entry '{}' in {}", serialToken, file);
                }
            }
            revoked = Set.copyOf(parsed);
        } catch (IOException e) {
            LOG.error("Could not read the certificate denylist at {}; keeping the previous set", file, e);
        }
    }
}
