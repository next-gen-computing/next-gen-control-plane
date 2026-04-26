package com.nextgen.desktop.service;

import com.nextgen.desktop.exception.DesktopException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ErrorHandler.
 */
class ErrorHandlerTest {

    private ErrorHandler errorHandler;

    @BeforeEach
    void setUp() {
        errorHandler = new ErrorHandler();
    }

    @Test
    void testExecuteWithRetrySuccess() throws DesktopException {
        AtomicInteger callCount = new AtomicInteger(0);

        String result = errorHandler.executeWithRetry("test-op", () -> {
            callCount.incrementAndGet();
            return "success";
        });

        assertEquals("success", result);
        assertEquals(1, callCount.get());
    }

    @Test
    void testExecuteWithRetryFailure() {
        AtomicInteger callCount = new AtomicInteger(0);

        assertThrows(DesktopException.class, () -> {
            errorHandler.executeWithRetry("test-op", () -> {
                callCount.incrementAndGet();
                throw new RuntimeException("Test error");
            }, 2);
        });

        assertEquals(2, callCount.get());
    }

    @Test
    void testExecuteWithRetrySuccessAfterFailure() throws DesktopException {
        AtomicInteger callCount = new AtomicInteger(0);

        String result = errorHandler.executeWithRetry("test-op", () -> {
            if (callCount.incrementAndGet() < 2) {
                throw new RuntimeException("Temporary error");
            }
            return "success";
        }, 3);

        assertEquals("success", result);
        assertEquals(2, callCount.get());
    }

    @Test
    void testCircuitBreakerOpensAfterFailures() {
        // Simulate multiple failures to open circuit
        for (int i = 0; i < 5; i++) {
            final int attempt = i;

            errorHandler.resetCircuit("circuit-test");
            try {
                errorHandler.executeWithRetry("circuit-test", () -> {
                    throw new RuntimeException("Error " + attempt);
                }, 1);
            } catch (DesktopException e) {
                // Expected
            }
        }

        // Circuit should be open now
        assertThrows(DesktopException.class, () -> {
            errorHandler.executeWithRetry("circuit-test", () -> "should not execute", 1);
        });
    }

    @Test
    void testDispose() {
        errorHandler.dispose();
        // Should complete without exception
    }
}
