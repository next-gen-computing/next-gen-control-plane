package com.nextgen.controlplane.alert;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

class CompositeAlertNotifierTest {

    /** A minimal, real (not mocked) AlertNotifier that just counts calls — real object, real interface
     * dispatch, the thing actually under test here is CompositeAlertNotifier's own fan-out/isolation
     * logic, not any particular channel's implementation. */
    private static final class CountingNotifier implements AlertNotifier {
        final AtomicInteger downCalls = new AtomicInteger();
        final AtomicInteger atRiskCalls = new AtomicInteger();

        @Override
        public void notifyNodeDown(String nodeId, String reason) {
            downCalls.incrementAndGet();
        }

        @Override
        public void notifyNodeAtRisk(String nodeId, double riskScore, String reason) {
            atRiskCalls.incrementAndGet();
        }
    }

    private static final class ThrowingNotifier implements AlertNotifier {
        @Override
        public void notifyNodeDown(String nodeId, String reason) {
            throw new RuntimeException("this channel is broken");
        }

        @Override
        public void notifyNodeAtRisk(String nodeId, double riskScore, String reason) {
            throw new RuntimeException("this channel is broken");
        }
    }

    @Test
    void notifyNodeDownReachesEveryConfiguredChannel() {
        CountingNotifier a = new CountingNotifier();
        CountingNotifier b = new CountingNotifier();
        CompositeAlertNotifier composite = new CompositeAlertNotifier(List.of(a, b));

        composite.notifyNodeDown("node-1", "no heartbeat");

        assertEquals(1, a.downCalls.get());
        assertEquals(1, b.downCalls.get());
    }

    @Test
    void notifyNodeAtRiskReachesEveryConfiguredChannel() {
        CountingNotifier a = new CountingNotifier();
        CountingNotifier b = new CountingNotifier();
        CompositeAlertNotifier composite = new CompositeAlertNotifier(List.of(a, b));

        composite.notifyNodeAtRisk("node-1", 0.9, "rising RTT");

        assertEquals(1, a.atRiskCalls.get());
        assertEquals(1, b.atRiskCalls.get());
    }

    /** The whole point of isolating each channel in its own try/catch: one broken channel must never
     * stop a different, working channel from receiving the same alert, and must never throw back into
     * the caller (a monitor's sweep thread) either. */
    @Test
    void aBrokenChannelNeitherBlocksTheOthersNorThrowsBackToTheCaller() {
        CountingNotifier working = new CountingNotifier();
        ThrowingNotifier broken = new ThrowingNotifier();
        CompositeAlertNotifier composite = new CompositeAlertNotifier(List.of(broken, working));

        assertDoesNotThrow(() -> composite.notifyNodeDown("node-1", "no heartbeat"));

        assertEquals(1, working.downCalls.get(), "the working channel after the broken one must still fire");
    }
}
