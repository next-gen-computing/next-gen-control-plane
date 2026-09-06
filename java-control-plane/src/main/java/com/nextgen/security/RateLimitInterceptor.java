package com.nextgen.security;

import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.prometheus.client.Counter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Rate-limits the enrolment endpoint.
 *
 * <p>Attached <b>per-service to enrolment only</b>, never server-wide: heartbeats run at one every
 * two seconds per node and must not share a policy sized for a once-per-lifetime operation.
 *
 * <p>Two tiers, per source IP and global. <b>The per-IP bucket is checked first</b> — if a per-IP
 * rejection also consumed a global token, one abusive source could drain the cluster-wide allowance
 * and lock out legitimate nodes, which is a denial of service delivered by the rate limiter itself.
 */
public class RateLimitInterceptor implements ServerInterceptor {
    private static final Logger LOG = LoggerFactory.getLogger(RateLimitInterceptor.class);

    private static final Counter REJECTED = Counter.build()
            .name("controlplane_enrollment_rate_limited_total")
            .help("Enrolment attempts rejected by the rate limiter")
            .labelNames("tier")
            .register();

    private static final Counter TABLE_FULL = Counter.build()
            .name("controlplane_enrollment_ratelimit_table_full_total")
            .help("Times the per-source bucket table was full and only the global limit applied")
            .register();

    private final RateLimitPolicy policy;
    private final LongSupplier nanoClock;
    private final Map<String, TokenBucket> perSource = new ConcurrentHashMap<>();
    private final TokenBucket globalBucket;

    public RateLimitInterceptor(RateLimitPolicy policy, LongSupplier nanoClock) {
        this.policy = policy;
        this.nanoClock = nanoClock;
        this.globalBucket = new TokenBucket(policy.globalCapacity(), policy.globalRefill(),
                policy.refillPeriod().toNanos(), nanoClock);
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {

        String key = bucketKey(call);
        if (key != null && !acquirePerSource(key)) {
            REJECTED.labels("per_ip").inc();
            return reject(call, "enrollment rate limit exceeded");
        }
        if (!globalBucket.tryAcquire()) {
            REJECTED.labels("global").inc();
            return reject(call, "enrollment rate limit exceeded");
        }
        return next.startCall(call, headers);
    }

    private boolean acquirePerSource(String key) {
        TokenBucket bucket = perSource.get(key);
        if (bucket == null) {
            // A spoofed-source flood would otherwise create one bucket per address: a memory DoS.
            // Past the cap, fall back to the global bucket alone rather than growing without bound.
            if (perSource.size() >= policy.maxTrackedKeys()) {
                TABLE_FULL.inc();
                return true;
            }
            bucket = perSource.computeIfAbsent(key, k -> new TokenBucket(
                    policy.perIpCapacity(), policy.perIpRefill(),
                    policy.refillPeriod().toNanos(), nanoClock));
        }
        return bucket.tryAcquire();
    }

    /** Drops buckets that are back at full capacity — a full bucket carries no state worth keeping. */
    public void sweepIdleBuckets() {
        perSource.entrySet().removeIf(entry -> entry.getValue().isFull());
    }

    int trackedSources() {
        return perSource.size();
    }

    private <ReqT, RespT> ServerCall.Listener<ReqT> reject(ServerCall<ReqT, RespT> call,
                                                           String description) {
        Metadata trailers = new Metadata();
        trailers.put(Metadata.Key.of("retry-after-millis", Metadata.ASCII_STRING_MARSHALLER),
                String.valueOf(policy.refillPeriod().toMillis()));
        // Never echo the token or the source address into the description: error messages end up in
        // agent logs and support tickets.
        call.close(Status.RESOURCE_EXHAUSTED.withDescription(description), trailers);
        LOG.warn("Enrolment attempt rate limited");
        // MUST NOT be null — gRPC NPEs on a null listener.
        return new ServerCall.Listener<>() {
        };
    }

    /**
     * The bucket key for a call, or null when no usable source address is available.
     *
     * <p>Four traps, each a real bug if skipped:
     * <ol>
     *   <li>{@code TRANSPORT_ATTR_REMOTE_ADDR} is null on the in-process transport — every in-process
     *       test would NPE.</li>
     *   <li>{@code getAddress()} is null for an unresolved {@link InetSocketAddress}.</li>
     *   <li>Per-IP limiting on IPv6 is worthless without prefix aggregation: a single host routinely
     *       owns a /64, giving it 2^64 independent buckets. Keying on the first 8 bytes closes that
     *       bypass, and incidentally strips the {@code %scope} suffix that would otherwise fragment
     *       buckets for one host.</li>
     *   <li>{@code X-Forwarded-For} is deliberately NOT consulted — it is attacker-controlled, and
     *       trusting it hands out unlimited buckets. Behind an L7 proxy the global bucket is the only
     *       real protection; that is documented rather than papered over.</li>
     * </ol>
     */
    static String bucketKey(ServerCall<?, ?> call) {
        SocketAddress remote = call.getAttributes().get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR);
        if (!(remote instanceof InetSocketAddress socketAddress)) {
            return null;
        }
        InetAddress address = socketAddress.getAddress();
        if (address == null) {
            return null;
        }
        if (address instanceof Inet6Address) {
            byte[] bytes = address.getAddress();
            return "v6:" + HexFormat.of().formatHex(bytes, 0, Math.min(8, bytes.length));
        }
        return "v4:" + address.getHostAddress();
    }
}
