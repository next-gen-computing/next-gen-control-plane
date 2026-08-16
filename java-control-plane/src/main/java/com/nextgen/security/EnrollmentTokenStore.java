package com.nextgen.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mints and consumes single-use enrolment tokens.
 *
 * <h2>Security properties</h2>
 *
 * <ul>
 *   <li><b>256 bits of entropy.</b> The token this replaces was 8 characters from a 32-symbol
 *       alphabet — 40 bits, brute-forceable in hours at 1000 requests/second. Rate limiting alone
 *       cannot rescue 40 bits, and 256 bits alone does not excuse skipping rate limiting; the system
 *       does both.</li>
 *   <li><b>The plaintext is never stored.</b> Only {@code SHA-256(token)} is kept, compared with
 *       {@link MessageDigest#isEqual} (constant time). A leaked store therefore does not yield usable
 *       tokens.</li>
 *   <li><b>Single use, enforced atomically.</b> {@code consume} is a {@link ConcurrentHashMap#remove},
 *       which is atomic — two concurrent enrolments presenting the same token produce exactly one
 *       winner, with no lock and no check-then-act race.</li>
 *   <li><b>Bound to a node id.</b> The bound id — not {@code EnrollRequest.node_id}, and certainly not
 *       the CSR subject — becomes the issued certificate's common name.</li>
 * </ul>
 */
public class EnrollmentTokenStore {
    private static final Logger LOG = LoggerFactory.getLogger(EnrollmentTokenStore.class);

    private static final int TOKEN_BYTES = 32;   // 256 bits

    private final Map<String, Entry> tokensByHash = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;
    private final Duration ttl;

    /** Stage II: null (every deployment before this) means this store is leader-local-only, exactly
     * today's behavior — a token minted by a since-superseded Raft leader is not known to the newly
     * elected one. Set via {@link #setReplicationSink}, not the constructor: {@code ControlPlaneServer.
     * start()} needs to build a {@code RaftStateMachine} that already references THIS store before a
     * {@code RaftNode} exists to build the sink from, so the sink is necessarily wired in after both
     * exist rather than at construction time. See {@code TokenReplicationSink}'s own Javadoc for what
     * "replicated" actually means here (hash-only, never the plaintext). */
    private volatile TokenReplicationSink replicationSink;

    public EnrollmentTokenStore(Clock clock, Duration ttl) {
        this.clock = clock;
        this.ttl = ttl;
    }

    /** See {@link #replicationSink}'s own Javadoc for why this is a late-bound setter, not a
     * constructor parameter. Real callers set this at most once, right after standing up Raft. */
    public void setReplicationSink(TokenReplicationSink replicationSink) {
        this.replicationSink = replicationSink;
    }

    /**
     * Mints a token for a node.
     *
     * @return the plaintext token. This is the only time it exists in a readable form; it is never
     *         logged and never persisted.
     */
    public String mint(String nodeId) {
        byte[] raw = new byte[TOKEN_BYTES];
        random.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        String tokenHash = hash(token);
        Instant expiresAt = clock.instant().plus(ttl);
        tokensByHash.put(tokenHash, new Entry(nodeId, expiresAt));
        // Stage II: called BEFORE returning the plaintext, deliberately — a blocking sink (the real
        // Raft one) means minting only reports success once a future leader is guaranteed to recognize
        // this exact token, not just "some token exists for this node id." A replication failure here
        // propagates as a real exception rather than silently handing back a token nobody else knows
        // about — see TokenReplicationSink's own Javadoc.
        if (replicationSink != null) {
            replicationSink.onMinted(nodeId, tokenHash, expiresAt.toEpochMilli());
        }
        LOG.info("Minted an enrolment token for node '{}' (expires in {} minutes)",
                nodeId, ttl.toMinutes());
        return token;
    }

    /**
     * Consumes a token, returning the node id it was bound to.
     *
     * <p>The token is removed whether or not it had expired, so a leaked expired token cannot be
     * retried indefinitely.
     */
    public Consumption consume(String token) {
        if (token == null || token.isBlank()) {
            return Consumption.invalid();
        }
        String tokenHash = hash(token);
        Entry entry = tokensByHash.remove(tokenHash);
        if (entry == null) {
            return Consumption.invalid();
        }
        // Stage II: notified AFTER the real (already-authoritative, already-atomic) local decision —
        // this is telling other replicas about something that already happened, not asking permission,
        // so it's safe (and correct — see TokenReplicationSink's own Javadoc) for this to be best-effort
        // async rather than blocking an enrollment response that has already succeeded.
        if (replicationSink != null) {
            replicationSink.onConsumed(tokenHash);
        }
        if (clock.instant().isAfter(entry.expiresAt())) {
            return Consumption.expired();
        }
        return Consumption.accepted(entry.nodeId());
    }

    /** Stage II: inserts a token entry exactly as {@link #mint} would have, but from an already-decided
     * hash rather than generating a new random plaintext — called from {@code RaftStateMachine.apply()}
     * on EVERY replica (including the one that originally minted it) so a newly-elected leader already
     * has this exact token without needing to have been the one that minted it. A second insert of the
     * identical (hash, nodeId, expiry) triple — which happens on the minting replica itself, since
     * {@code apply()} runs there too — is a harmless idempotent overwrite, not a conflict. */
    public void restoreMinted(String nodeId, String tokenHash, long expiresAtEpochMillis) {
        tokensByHash.put(tokenHash, new Entry(nodeId, Instant.ofEpochMilli(expiresAtEpochMillis)));
    }

    /** Stage II: the replication counterpart of {@link #restoreMinted} for consumption — removes the
     * entry if present, a no-op if it's already gone (e.g. on the replica that consumed it locally and
     * is now just receiving its own replicated notification back). Never reports an outcome — by the
     * time this runs, the real accept/invalid/expired decision was already made wherever the original
     * {@link #consume} call happened; this only propagates the resulting removal. */
    public void removeIfPresentByHash(String tokenHash) {
        tokensByHash.remove(tokenHash);
    }

    /** Removes tokens that have expired. Bounds memory when tokens are minted but never used. */
    public int purgeExpired() {
        Instant now = clock.instant();
        int before = tokensByHash.size();
        tokensByHash.values().removeIf(entry -> now.isAfter(entry.expiresAt()));
        return before - tokensByHash.size();
    }

    public int size() {
        return tokensByHash.size();
    }

    /** Looks up the node a token is bound to without consuming it. Test seam only — public so
     * cross-package integration tests (e.g. Stage II's Raft-replication proof) can use it too. */
    public Optional<String> peek(String token) {
        Entry entry = tokensByHash.get(hash(token));
        return entry == null ? Optional.empty() : Optional.of(entry.nodeId());
    }

    private static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required but unavailable", e);
        }
    }

    private record Entry(String nodeId, Instant expiresAt) {
    }

    /** Outcome of consuming a token. */
    public record Consumption(String nodeId, Status status) {

        public enum Status { ACCEPTED, INVALID, EXPIRED }

        static Consumption accepted(String nodeId) {
            return new Consumption(nodeId, Status.ACCEPTED);
        }

        static Consumption invalid() {
            return new Consumption(null, Status.INVALID);
        }

        static Consumption expired() {
            return new Consumption(null, Status.EXPIRED);
        }

        public boolean accepted() {
            return status == Status.ACCEPTED;
        }
    }
}
