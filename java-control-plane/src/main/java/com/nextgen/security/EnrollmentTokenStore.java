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

    public EnrollmentTokenStore(Clock clock, Duration ttl) {
        this.clock = clock;
        this.ttl = ttl;
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
        tokensByHash.put(hash(token), new Entry(nodeId, clock.instant().plus(ttl)));
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
        Entry entry = tokensByHash.remove(hash(token));
        if (entry == null) {
            return Consumption.invalid();
        }
        if (clock.instant().isAfter(entry.expiresAt())) {
            return Consumption.expired();
        }
        return Consumption.accepted(entry.nodeId());
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

    /** Looks up the node a token is bound to without consuming it. Test seam only. */
    Optional<String> peek(String token) {
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
