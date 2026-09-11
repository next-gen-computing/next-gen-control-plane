package com.nextgen.desktop.ui.service;

import javafx.application.Platform;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Tracks and publishes the connection state so every screen can show it.
 *
 * <p>Observable via JavaFX properties, which is what lets a single header strip in {@code MainWindow}
 * reflect connectivity across all five screens without each one polling.
 *
 * <p>A single failure moves the app to {@link ConnectionState#RECONNECTING} rather than straight to
 * {@code DISCONNECTED}: one dropped poll on a WAN link is normal and should not make the UI flap.
 * After {@code failuresBeforeDisconnected} consecutive failures the state escalates.
 */
public class ConnectionStateManager {

    /** Consecutive failures tolerated before the UI declares the link down. */
    static final int DEFAULT_FAILURES_BEFORE_DISCONNECTED = 3;

    private final ObjectProperty<ConnectionState> state =
            new SimpleObjectProperty<>(ConnectionState.IDLE);
    private final StringProperty detail = new SimpleStringProperty("Not connected");
    private final StringProperty lastSuccessDescription = new SimpleStringProperty("Never");
    private final ObjectProperty<Instant> lastSuccess = new SimpleObjectProperty<>(null);

    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final int failuresBeforeDisconnected;
    private final Supplier<Instant> clock;
    private final boolean fxThreadDispatch;

    public ConnectionStateManager() {
        this(DEFAULT_FAILURES_BEFORE_DISCONNECTED, Instant::now, true);
    }

    /**
     * @param fxThreadDispatch when true, property writes are marshalled onto the JavaFX application
     *                         thread. Tests pass false so they can run without a toolkit.
     */
    ConnectionStateManager(int failuresBeforeDisconnected, Supplier<Instant> clock,
                           boolean fxThreadDispatch) {
        this.failuresBeforeDisconnected = failuresBeforeDisconnected;
        this.clock = clock;
        this.fxThreadDispatch = fxThreadDispatch;
    }

    // ── Transitions ──────────────────────────────────────────────────────────

    /** Records a successful exchange with the control plane. */
    public void recordSuccess() {
        consecutiveFailures.set(0);
        Instant now = clock.get();
        run(() -> {
            lastSuccess.set(now);
            lastSuccessDescription.set("just now");
            detail.set("Live");
            state.set(ConnectionState.CONNECTED);
        });
    }

    /**
     * Records a failed exchange.
     *
     * @param reason short, user-facing cause. Shown verbatim, so it must not contain a stack trace.
     */
    public void recordFailure(String reason) {
        int failures = consecutiveFailures.incrementAndGet();
        ConnectionState next = failures >= failuresBeforeDisconnected
                ? ConnectionState.DISCONNECTED
                : ConnectionState.RECONNECTING;
        String message = reason == null || reason.isBlank() ? "connection failed" : reason;
        run(() -> {
            detail.set(next == ConnectionState.DISCONNECTED
                    ? message + " — showing last known data"
                    : message + " (attempt " + failures + ")");
            state.set(next);
        });
    }

    /** Records that a connection attempt is under way. */
    public void recordAttempt() {
        run(() -> {
            if (state.get() != ConnectionState.CONNECTED) {
                state.set(ConnectionState.RECONNECTING);
                detail.set("Connecting…");
            }
        });
    }

    /** Resets to the idle state, e.g. after the user disconnects deliberately. */
    public void reset() {
        consecutiveFailures.set(0);
        run(() -> {
            state.set(ConnectionState.IDLE);
            detail.set("Not connected");
        });
    }

    /** Refreshes the human-readable age of the last successful update. */
    public void refreshStaleness() {
        Instant last = lastSuccess.get();
        if (last == null) {
            run(() -> lastSuccessDescription.set("Never"));
            return;
        }
        String description = describeAge(Duration.between(last, clock.get()));
        run(() -> lastSuccessDescription.set(description));
    }

    static String describeAge(Duration age) {
        long seconds = Math.max(0, age.getSeconds());
        if (seconds < 5) {
            return "just now";
        }
        if (seconds < 60) {
            return seconds + "s ago";
        }
        if (seconds < 3600) {
            return (seconds / 60) + "m ago";
        }
        return (seconds / 3600) + "h ago";
    }

    // ── Accessors ────────────────────────────────────────────────────────────

    public ReadOnlyObjectProperty<ConnectionState> stateProperty() {
        return state;
    }

    public ConnectionState getState() {
        return state.get();
    }

    /** True when on-screen data reflects a successful poll. */
    public boolean isLive() {
        return state.get().isLive();
    }

    public ReadOnlyStringProperty detailProperty() {
        return detail;
    }

    public String getDetail() {
        return detail.get();
    }

    public ReadOnlyStringProperty lastSuccessDescriptionProperty() {
        return lastSuccessDescription;
    }

    public Instant getLastSuccess() {
        return lastSuccess.get();
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures.get();
    }

    private void run(Runnable action) {
        if (fxThreadDispatch && !Platform.isFxApplicationThread()) {
            Platform.runLater(action);
        } else {
            action.run();
        }
    }
}
