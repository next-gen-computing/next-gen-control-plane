package com.nextgen.agent.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nextgen.controlplane.task.TaskKindDomain;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks the segmented-sieve prime counter against known π(x) values — this is the whole point of
 * choosing prime counting as the first real workload: correctness is independently verifiable, not
 * just "the code ran without throwing."
 */
class PrimeRangeCounterExecutorTest {

    private final PrimeRangeCounterExecutor executor = new PrimeRangeCounterExecutor();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void reportsItsKind() {
        assertEquals(TaskKindDomain.PRIME_COUNT_RANGE, executor.kind());
    }

    @Test
    void countsPrimesUpTo100() {
        // pi(100) = 25 — half-open range, so 101 is the exclusive upper bound to include 100 itself
        // (though 100 is not prime, the boundary must still be handled correctly).
        assertEquals(25, PrimeRangeCounterExecutor.countPrimesInRange(0, 101));
    }

    @Test
    void countsPrimesUpTo1000() {
        assertEquals(168, PrimeRangeCounterExecutor.countPrimesInRange(0, 1001));
    }

    @Test
    void countsPrimesUpTo10000() {
        assertEquals(1229, PrimeRangeCounterExecutor.countPrimesInRange(0, 10001));
    }

    @Test
    void countsPrimesInAMidRangeSegmentNotStartingAtZero() {
        // Known primes strictly between 100 and 200 (exclusive end at 200): 101, 103, 107, 109, 113,
        // 127, 131, 137, 139, 149, 151, 157, 163, 167, 173, 179, 181, 191, 193, 197, 199 -> 21 primes.
        assertEquals(21, PrimeRangeCounterExecutor.countPrimesInRange(100, 200));
    }

    @Test
    void emptyRangeCountsZero() {
        assertEquals(0, PrimeRangeCounterExecutor.countPrimesInRange(50, 50));
    }

    @Test
    void rangeEntirelyBelowTwoCountsZero() {
        assertEquals(0, PrimeRangeCounterExecutor.countPrimesInRange(0, 2));
    }

    /** No-op sink — {@link PrimeRangeCounterExecutor} has nothing incremental to report; this is the
     * normal case for most executors, per {@link TaskEventSink}'s own Javadoc. */
    private static final TaskEventSink NO_OP_SINK = (line, stderr) -> { };

    @Test
    void executeParsesPayloadAndReturnsResultJson() throws Exception {
        String resultJson = executor.execute("t1", "{\"range_start\":0,\"range_end\":101}", NO_OP_SINK);

        JsonNode result = mapper.readTree(resultJson);
        assertEquals(25, result.get("prime_count").asLong());
        assertEquals(0, result.get("range_start").asLong());
        assertEquals(101, result.get("range_end").asLong());
    }

    @Test
    void executeRejectsAnInvertedRange() {
        assertThrows(IllegalArgumentException.class,
                () -> executor.execute("t1", "{\"range_start\":100,\"range_end\":0}", NO_OP_SINK));
    }

    @Test
    void aRangeExceedingTheSegmentLimitIsRejectedHonestlyInsteadOfOverflowing() {
        // Found live: range_end=4_000_000_000 (comfortably a valid long) previously overflowed the
        // internal int cast into a negative array size and failed with a confusing exception instead
        // of a clear one. rangeStart=2 makes `lo` exactly rangeStart, so the actual segment size is
        // exactly rangeEnd - 2 — one past the limit, the precise boundary that used to break.
        long rangeEnd = 2 + PrimeRangeCounterExecutor.MAX_SEGMENT_SIZE + 1;

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> PrimeRangeCounterExecutor.countPrimesInRange(2, rangeEnd));
        assertTrue(e.getMessage().contains("job"), "the message should point at the real fix: " + e.getMessage());
    }

    @Test
    void aRangeExactlyAtTheSegmentLimitIsAccepted() {
        // Only checks that no exception is thrown and the sieve runs to completion — correctness at
        // this scale is already covered by the smaller known-π(x) cases above.
        long rangeEnd = 2 + PrimeRangeCounterExecutor.MAX_SEGMENT_SIZE;
        assertDoesNotThrow(() -> PrimeRangeCounterExecutor.countPrimesInRange(2, rangeEnd));
    }
}
