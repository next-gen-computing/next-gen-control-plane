package com.nextgen.security;

import io.grpc.Attributes;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.Status;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests for the enrolment rate limiter, including the accounting-order and IPv6 regressions.
 */
class RateLimitInterceptorTest {

    /** A call whose remote address is the given host, or in-process when host is null. */
    @SuppressWarnings("unchecked")
    private static ServerCall<Object, Object> callFrom(String host, AtomicReference<Status> closed) {
        ServerCall<Object, Object> call = mock(ServerCall.class);
        Attributes attributes = host == null
                ? Attributes.EMPTY
                : Attributes.newBuilder()
                        .set(Grpc.TRANSPORT_ATTR_REMOTE_ADDR, new InetSocketAddress(host, 40000))
                        .build();
        when(call.getAttributes()).thenReturn(attributes);
        MethodDescriptor<Object, Object> descriptor = mock(MethodDescriptor.class);
        when(descriptor.getFullMethodName()).thenReturn("nextgen.v1.NodeEnrollment/Enroll");
        when(call.getMethodDescriptor()).thenReturn(descriptor);
        if (closed != null) {
            doAnswer(invocation -> {
                closed.set(invocation.getArgument(0));
                return null;
            }).when(call).close(any(Status.class), any(Metadata.class));
        }
        return call;
    }

    @SuppressWarnings("unchecked")
    private static ServerCallHandler<Object, Object> countingHandler(AtomicInteger allowed) {
        ServerCallHandler<Object, Object> handler = mock(ServerCallHandler.class);
        when(handler.startCall(any(), any())).thenAnswer(invocation -> {
            allowed.incrementAndGet();
            return mock(ServerCall.Listener.class);
        });
        return handler;
    }

    private static RateLimitPolicy policy(double perIpBurst, double globalBurst) {
        return new RateLimitPolicy(perIpBurst, 1, globalBurst, 10, Duration.ofMinutes(1), 10_000);
    }

    @Test
    void allowsTheFirstCallsFromOneSource() {
        AtomicInteger allowed = new AtomicInteger();
        RateLimitInterceptor interceptor =
                new RateLimitInterceptor(policy(5, 50), new AtomicLong(0)::get);

        for (int i = 0; i < 5; i++) {
            interceptor.interceptCall(callFrom("10.0.0.1", null), new Metadata(),
                    countingHandler(allowed));
        }

        assertEquals(5, allowed.get());
    }

    @Test
    void furtherCallsFromTheSameSourceAreResourceExhausted() {
        AtomicInteger allowed = new AtomicInteger();
        AtomicReference<Status> closed = new AtomicReference<>();
        RateLimitInterceptor interceptor =
                new RateLimitInterceptor(policy(2, 50), new AtomicLong(0)::get);

        for (int i = 0; i < 2; i++) {
            interceptor.interceptCall(callFrom("10.0.0.1", null), new Metadata(),
                    countingHandler(allowed));
        }
        interceptor.interceptCall(callFrom("10.0.0.1", closed), new Metadata(),
                countingHandler(allowed));

        assertEquals(2, allowed.get());
        assertNotNull(closed.get());
        assertEquals(Status.Code.RESOURCE_EXHAUSTED, closed.get().getCode());
    }

    @Test
    void rejectionDescriptionLeaksNeitherTokenNorAddress() {
        AtomicReference<Status> closed = new AtomicReference<>();
        RateLimitInterceptor interceptor =
                new RateLimitInterceptor(policy(1, 50), new AtomicLong(0)::get);
        interceptor.interceptCall(callFrom("10.0.0.1", null), new Metadata(),
                countingHandler(new AtomicInteger()));
        interceptor.interceptCall(callFrom("10.0.0.1", closed), new Metadata(),
                countingHandler(new AtomicInteger()));

        String description = String.valueOf(closed.get().getDescription());

        // Error messages end up in agent logs and support tickets.
        assertFalse(description.contains("10.0.0.1"), description);
        assertEquals("enrollment rate limit exceeded", description);
    }

    @Test
    void rejectedCallReturnsANonNullListener() {
        RateLimitInterceptor interceptor =
                new RateLimitInterceptor(policy(0.5, 50), new AtomicLong(0)::get);

        ServerCall.Listener<Object> listener = interceptor.interceptCall(
                callFrom("10.0.0.1", new AtomicReference<>()), new Metadata(),
                countingHandler(new AtomicInteger()));

        // gRPC dereferences this and NPEs on null.
        assertNotNull(listener);
    }

    @Test
    void differentSourcesHaveIndependentBuckets() {
        AtomicInteger allowed = new AtomicInteger();
        RateLimitInterceptor interceptor =
                new RateLimitInterceptor(policy(2, 50), new AtomicLong(0)::get);

        for (int i = 0; i < 2; i++) {
            interceptor.interceptCall(callFrom("10.0.0.1", null), new Metadata(), countingHandler(allowed));
            interceptor.interceptCall(callFrom("10.0.0.2", null), new Metadata(), countingHandler(allowed));
        }

        assertEquals(4, allowed.get());
    }

    @Test
    void perSourceRejectionDoesNotConsumeGlobalBudget() {
        // The accounting-order regression: if a per-IP rejection also burned a global token, one
        // abusive source could drain the cluster-wide allowance and lock everyone else out — a denial
        // of service delivered by the rate limiter itself.
        AtomicInteger allowed = new AtomicInteger();
        RateLimitInterceptor interceptor =
                new RateLimitInterceptor(policy(1, 5), new AtomicLong(0)::get);

        interceptor.interceptCall(callFrom("10.0.0.1", null), new Metadata(), countingHandler(allowed));
        for (int i = 0; i < 20; i++) {
            interceptor.interceptCall(callFrom("10.0.0.1", new AtomicReference<>()), new Metadata(),
                    countingHandler(allowed));
        }

        // Four other, legitimate sources must still find global budget available. They are distinct
        // addresses because the per-IP limit (capacity 1 here) would otherwise cap them itself and
        // the test would prove nothing about the global bucket.
        AtomicInteger legitimate = new AtomicInteger();
        for (int i = 1; i <= 4; i++) {
            interceptor.interceptCall(callFrom("10.0.0.1" + i, null), new Metadata(),
                    countingHandler(legitimate));
        }

        assertEquals(4, legitimate.get(), "the abusive source must not have drained the global bucket");
    }

    @Test
    void globalBucketRejectsEvenWhenPerSourceAllows() {
        AtomicInteger allowed = new AtomicInteger();
        AtomicReference<Status> closed = new AtomicReference<>();
        RateLimitInterceptor interceptor =
                new RateLimitInterceptor(policy(100, 3), new AtomicLong(0)::get);

        for (int i = 0; i < 3; i++) {
            interceptor.interceptCall(callFrom("10.0.0." + i, null), new Metadata(),
                    countingHandler(allowed));
        }
        interceptor.interceptCall(callFrom("10.0.0.99", closed), new Metadata(),
                countingHandler(allowed));

        assertEquals(3, allowed.get());
        assertEquals(Status.Code.RESOURCE_EXHAUSTED, closed.get().getCode());
    }

    @Test
    void ipv6AddressesInTheSameSlash64ShareOneBucket() {
        // A single host routinely owns a /64. Without prefix aggregation it would have 2^64
        // independent buckets, making per-source limiting meaningless.
        AtomicInteger allowed = new AtomicInteger();
        RateLimitInterceptor interceptor =
                new RateLimitInterceptor(policy(2, 50), new AtomicLong(0)::get);

        interceptor.interceptCall(callFrom("2001:db8::1", null), new Metadata(), countingHandler(allowed));
        interceptor.interceptCall(callFrom("2001:db8::2", null), new Metadata(), countingHandler(allowed));
        interceptor.interceptCall(callFrom("2001:db8::3", new AtomicReference<>()), new Metadata(),
                countingHandler(allowed));

        assertEquals(2, allowed.get(), "addresses in one /64 must share a bucket");
    }

    @Test
    void inProcessTransportWithoutARemoteAddressFallsBackToGlobalOnly() {
        AtomicInteger allowed = new AtomicInteger();
        RateLimitInterceptor interceptor =
                new RateLimitInterceptor(policy(1, 10), new AtomicLong(0)::get);

        // TRANSPORT_ATTR_REMOTE_ADDR is null on the in-process transport; unguarded this NPEs.
        for (int i = 0; i < 5; i++) {
            assertDoesNotThrow(() -> interceptor.interceptCall(callFrom(null, null), new Metadata(),
                    countingHandler(allowed)));
        }

        assertEquals(5, allowed.get());
    }

    @Test
    void bucketTableGrowthIsCapped() {
        RateLimitInterceptor interceptor = new RateLimitInterceptor(
                new RateLimitPolicy(5, 1, 1_000_000, 1_000_000, Duration.ofMinutes(1), 100),
                new AtomicLong(0)::get);

        // A spoofed-source flood would otherwise create one bucket per address: a memory DoS.
        for (int i = 0; i < 500; i++) {
            interceptor.interceptCall(callFrom("10.1." + (i / 256) + "." + (i % 256), null),
                    new Metadata(), countingHandler(new AtomicInteger()));
        }

        assertTrue(interceptor.trackedSources() <= 100,
                "tracked sources grew to " + interceptor.trackedSources());
    }

    @Test
    void idleBucketsAreSweptAway() {
        AtomicLong clock = new AtomicLong(0);
        RateLimitInterceptor interceptor = new RateLimitInterceptor(policy(5, 50), clock::get);
        interceptor.interceptCall(callFrom("10.0.0.1", null), new Metadata(),
                countingHandler(new AtomicInteger()));
        assertEquals(1, interceptor.trackedSources());

        clock.addAndGet(Duration.ofHours(1).toNanos());
        interceptor.sweepIdleBuckets();

        assertEquals(0, interceptor.trackedSources());
    }

    @Test
    void tokensRefillOverTime() {
        AtomicLong clock = new AtomicLong(0);
        AtomicInteger allowed = new AtomicInteger();
        RateLimitInterceptor interceptor = new RateLimitInterceptor(policy(1, 50), clock::get);

        interceptor.interceptCall(callFrom("10.0.0.1", null), new Metadata(), countingHandler(allowed));
        interceptor.interceptCall(callFrom("10.0.0.1", new AtomicReference<>()), new Metadata(),
                countingHandler(allowed));
        assertEquals(1, allowed.get());

        clock.addAndGet(Duration.ofMinutes(1).toNanos());
        interceptor.interceptCall(callFrom("10.0.0.1", null), new Metadata(), countingHandler(allowed));

        assertEquals(2, allowed.get());
    }
}
