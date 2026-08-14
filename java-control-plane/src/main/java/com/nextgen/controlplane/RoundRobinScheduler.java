package com.nextgen.controlplane;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Round-robin task placement that rotates over node <i>identity</i> rather than list position.
 *
 * <h2>Why not an incrementing index</h2>
 *
 * The previous implementation was {@code Math.abs(counter.getAndIncrement()) % aliveNodes.size()},
 * which had three defects:
 *
 * <ol>
 *   <li><b>The candidate list was built from {@code ConcurrentHashMap.values()}</b>, whose iteration
 *       order is unspecified and changes on resize. Index <i>i</i> was therefore not stably the same
 *       node from one call to the next, so the rotation was not actually round-robin.</li>
 *   <li><b>The modulus base changed as nodes died.</b> Counter values 0,1,2,3,4 against sizes
 *       3,3,2,2,3 yield indices 0,1,0,1,2 — index 2 is starved while 0 and 1 are double-loaded.</li>
 *   <li><b>{@code Math.abs(Integer.MIN_VALUE)} is still negative</b>, so after 2^31 submissions the
 *       modulus went negative and {@code List.get} threw, failing the RPC.</li>
 * </ol>
 *
 * <p>Rotating over identity fixes all three. The cursor stores the id of the node picked last; the
 * next pick is the first node strictly greater than it in a node-id-sorted candidate list, wrapping
 * to the first element. "Next after X" is meaningful across membership changes — adding or removing
 * a node neither restarts nor skips the cycle — and there is no integer to overflow.
 *
 * <p>This class has no gRPC or registry dependency, so it is directly unit-testable.
 */
public final class RoundRobinScheduler {

    /** Id of the most recently selected node. The empty string sorts before every real id. */
    private final AtomicReference<String> cursor = new AtomicReference<>("");

    /**
     * Selects the next node.
     *
     * @param candidatesSortedById schedulable nodes, sorted ascending by node id. Use
     *                             {@link NodeRegistry#aliveSnapshot()}, which guarantees both.
     * @return the selected node, or empty when there are no candidates.
     */
    public Optional<NodeRecord> select(List<NodeRecord> candidatesSortedById) {
        if (candidatesSortedById == null || candidatesSortedById.isEmpty()) {
            return Optional.empty();
        }
        while (true) {
            String previous = cursor.get();
            NodeRecord pick = firstStrictlyGreaterThan(previous, candidatesSortedById);
            // CAS rather than set: concurrent submissions must not both advance from the same cursor
            // value and hand the same node two tasks in a row.
            if (cursor.compareAndSet(previous, pick.getNodeId())) {
                return Optional.of(pick);
            }
        }
    }

    /** The id most recently handed out, or the empty string before the first selection. */
    public String currentCursor() {
        return cursor.get();
    }

    /** Test seam: positions the cursor as though {@code nodeId} had just been selected. */
    void seedCursor(String nodeId) {
        cursor.set(nodeId == null ? "" : nodeId);
    }

    /**
     * Binary search for the first element whose id sorts strictly after {@code previous}, wrapping to
     * index 0 when {@code previous} is at or past the end.
     */
    private static NodeRecord firstStrictlyGreaterThan(String previous, List<NodeRecord> sorted) {
        int low = 0;
        int high = sorted.size();
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (sorted.get(mid).getNodeId().compareTo(previous) > 0) {
                high = mid;
            } else {
                low = mid + 1;
            }
        }
        return low < sorted.size() ? sorted.get(low) : sorted.get(0);
    }
}
