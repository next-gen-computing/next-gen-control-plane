package com.nextgen.desktop.ui.service;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ConnectionStateManager.
 *
 * <p>Constructed with {@code fxThreadDispatch=false} so property writes happen on the calling thread
 * and no JavaFX toolkit is needed.
 */
class ConnectionStateManagerTest {

    private static ConnectionStateManager manager(AtomicReference<Instant> clock) {
        return new ConnectionStateManager(3, clock::get, false);
    }

    private static ConnectionStateManager manager() {
        return manager(new AtomicReference<>(Instant.parse("2026-08-07T10:00:00Z")));
    }

    @Test
    void startsIdle() {
        ConnectionStateManager manager = manager();

        assertEquals(ConnectionState.IDLE, manager.getState());
        assertFalse(manager.isLive());
        assertNull(manager.getLastSuccess());
    }

    @Test
    void successMovesToConnected() {
        ConnectionStateManager manager = manager();

        manager.recordSuccess();

        assertEquals(ConnectionState.CONNECTED, manager.getState());
        assertTrue(manager.isLive());
        assertNotNull(manager.getLastSuccess());
    }

    @Test
    void singleFailureShowsReconnectingNotDisconnected() {
        ConnectionStateManager manager = manager();
        manager.recordSuccess();

        manager.recordFailure("Control plane unreachable");

        // One dropped poll on a WAN link is normal; flapping straight to DISCONNECTED would make the
        // UI unreadable during ordinary jitter.
        assertEquals(ConnectionState.RECONNECTING, manager.getState());
        assertFalse(manager.isLive());
    }

    @Test
    void repeatedFailuresEscalateToDisconnected() {
        ConnectionStateManager manager = manager();
        manager.recordSuccess();

        manager.recordFailure("boom");
        manager.recordFailure("boom");
        manager.recordFailure("boom");

        assertEquals(ConnectionState.DISCONNECTED, manager.getState());
        assertEquals(3, manager.getConsecutiveFailures());
    }

    @Test
    void disconnectedDetailSaysDataIsStale() {
        ConnectionStateManager manager = manager();
        for (int i = 0; i < 3; i++) {
            manager.recordFailure("Control plane unreachable");
        }

        assertTrue(manager.getDetail().contains("last known data"),
                "the user must be told on-screen data is no longer live: " + manager.getDetail());
    }

    @Test
    void successAfterFailuresResetsTheCounter() {
        ConnectionStateManager manager = manager();
        manager.recordFailure("boom");
        manager.recordFailure("boom");

        manager.recordSuccess();

        assertEquals(0, manager.getConsecutiveFailures());
        assertEquals(ConnectionState.CONNECTED, manager.getState());
    }

    @Test
    void blankFailureReasonFallsBackToAGenericMessage() {
        ConnectionStateManager manager = manager();

        manager.recordFailure("   ");

        assertTrue(manager.getDetail().contains("connection failed"), manager.getDetail());
    }

    @Test
    void attemptDoesNotDowngradeAnAlreadyConnectedState() {
        ConnectionStateManager manager = manager();
        manager.recordSuccess();

        manager.recordAttempt();

        assertEquals(ConnectionState.CONNECTED, manager.getState());
    }

    @Test
    void resetReturnsToIdle() {
        ConnectionStateManager manager = manager();
        manager.recordFailure("boom");

        manager.reset();

        assertEquals(ConnectionState.IDLE, manager.getState());
        assertEquals(0, manager.getConsecutiveFailures());
    }

    @Test
    void stalenessDescriptionAgesWithTheClock() {
        AtomicReference<Instant> clock = new AtomicReference<>(Instant.parse("2026-08-07T10:00:00Z"));
        ConnectionStateManager manager = manager(clock);
        manager.recordSuccess();

        clock.set(clock.get().plusSeconds(90));
        manager.refreshStaleness();

        // A frozen feed must visibly age rather than reading "just now" forever.
        assertEquals("1m ago", manager.lastSuccessDescriptionProperty().get());
    }

    @Test
    void stalenessIsNeverWhenNothingHasSucceeded() {
        ConnectionStateManager manager = manager();

        manager.refreshStaleness();

        assertEquals("Never", manager.lastSuccessDescriptionProperty().get());
    }

    @Test
    void ageDescriptionsCoverEachBand() {
        assertEquals("just now", ConnectionStateManager.describeAge(Duration.ofSeconds(2)));
        assertEquals("30s ago", ConnectionStateManager.describeAge(Duration.ofSeconds(30)));
        assertEquals("5m ago", ConnectionStateManager.describeAge(Duration.ofMinutes(5)));
        assertEquals("2h ago", ConnectionStateManager.describeAge(Duration.ofHours(2)));
    }

    @Test
    void negativeAgeIsClampedRatherThanRenderingNonsense() {
        assertEquals("just now", ConnectionStateManager.describeAge(Duration.ofSeconds(-10)));
    }

    @Test
    void onlyConnectedIsConsideredLive() {
        assertTrue(ConnectionState.CONNECTED.isLive());
        assertFalse(ConnectionState.RECONNECTING.isLive());
        assertFalse(ConnectionState.DISCONNECTED.isLive());
        assertFalse(ConnectionState.IDLE.isLive());
    }

    @Test
    void everyStateHasALabelAndColour() {
        for (ConnectionState state : ConnectionState.values()) {
            assertNotNull(state.label());
            assertFalse(state.label().isBlank());
            assertTrue(state.colorHex().matches("#[0-9A-Fa-f]{6}"),
                    state + " has a malformed colour: " + state.colorHex());
        }
    }
}
