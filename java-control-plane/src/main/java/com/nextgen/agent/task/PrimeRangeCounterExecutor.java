package com.nextgen.agent.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nextgen.controlplane.task.TaskKindDomain;

import java.util.ArrayList;
import java.util.List;

/**
 * Counts primes in the half-open range {@code [range_start, range_end)} via a segmented sieve of
 * Eratosthenes — the first real, CPU-bound, independently-checkable workload this project actually
 * dispatches and executes, replacing the previous {@code PRIME_COUNTER} task type that never ran
 * anywhere. Correctness is checkable against known π(x) values, e.g. π(100)=25, π(1000)=168.
 *
 * <p>Payload/result are plain JSON so no proto change is needed to evolve either shape:
 * {@code {"range_start": N, "range_end": M}} in, {@code {"range_start": N, "range_end": M,
 * "prime_count": K}} out.
 */
public final class PrimeRangeCounterExecutor implements TaskExecutor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * The segmented sieve holds one {@code boolean} per integer in the range in memory at once, so
     * the segment size is bounded well under {@code Integer.MAX_VALUE} — both because a Java array
     * cannot exceed that many elements, and because a segment anywhere near that size would demand
     * gigabytes of heap for one task. A caller wanting to count primes over a bigger range should
     * split it into a job (see {@code JobCoordinator}) instead of one oversized task.
     */
    static final long MAX_SEGMENT_SIZE = 200_000_000L;

    @Override
    public TaskKindDomain kind() {
        return TaskKindDomain.PRIME_COUNT_RANGE;
    }

    @Override
    public String execute(String taskId, String payloadJson, TaskEventSink events) throws Exception {
        JsonNode payload = MAPPER.readTree(payloadJson);
        long rangeStart = payload.path("range_start").asLong();
        long rangeEnd = payload.path("range_end").asLong();
        if (rangeEnd < rangeStart) {
            throw new IllegalArgumentException(
                    "range_end (" + rangeEnd + ") must be >= range_start (" + rangeStart + ")");
        }

        long count = countPrimesInRange(rangeStart, rangeEnd);

        return MAPPER.createObjectNode()
                .put("range_start", rangeStart)
                .put("range_end", rangeEnd)
                .put("prime_count", count)
                .toString();
    }

    /** Counts primes in {@code [rangeStart, rangeEnd)} using a segmented sieve of Eratosthenes. */
    static long countPrimesInRange(long rangeStart, long rangeEnd) {
        long lo = Math.max(rangeStart, 2);
        if (lo >= rangeEnd) {
            return 0;
        }

        long segmentSizeLong = rangeEnd - lo;
        if (segmentSizeLong > MAX_SEGMENT_SIZE) {
            // Without this check, a large-enough range silently overflows the int cast below into a
            // negative array size and fails with a confusing NegativeArraySizeException — this rejects
            // it honestly, up front, with a message that points at the real fix (a job).
            throw new IllegalArgumentException("range [" + rangeStart + ", " + rangeEnd + ") spans "
                    + segmentSizeLong + " integers, which exceeds the " + MAX_SEGMENT_SIZE
                    + "-integer limit for a single task — split it into a job with more sub-tasks instead");
        }

        long highestValue = rangeEnd - 1;
        int sqrtLimit = (int) Math.sqrt((double) highestValue) + 1;
        List<Integer> smallPrimes = simpleSieve(sqrtLimit);

        int segmentSize = (int) segmentSizeLong;
        boolean[] isComposite = new boolean[segmentSize];

        for (int p : smallPrimes) {
            long firstMultiple = Math.max((long) p * p, ((lo + p - 1) / p) * (long) p);
            for (long multiple = firstMultiple; multiple < rangeEnd; multiple += p) {
                isComposite[(int) (multiple - lo)] = true;
            }
        }

        long count = 0;
        for (boolean composite : isComposite) {
            if (!composite) {
                count++;
            }
        }
        return count;
    }

    /** Primes up to and including {@code limit}, via a plain sieve of Eratosthenes. */
    private static List<Integer> simpleSieve(int limit) {
        if (limit < 2) {
            return List.of();
        }
        boolean[] isComposite = new boolean[limit + 1];
        List<Integer> primes = new ArrayList<>();
        for (int i = 2; i <= limit; i++) {
            if (!isComposite[i]) {
                primes.add(i);
                for (long j = (long) i * i; j <= limit; j += i) {
                    isComposite[(int) j] = true;
                }
            }
        }
        return primes;
    }
}
