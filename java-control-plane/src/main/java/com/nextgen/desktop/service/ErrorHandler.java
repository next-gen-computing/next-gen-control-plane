package com.nextgen.desktop.service;

import com.nextgen.desktop.exception.DesktopException;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Global error handler with retry logic and user notifications.
 * Provides circuit breaker pattern for transient failures.
 */
public class ErrorHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ErrorHandler.class);
    
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 2000;
    
    // Circuit breaker state
    private final java.util.concurrent.ConcurrentHashMap<String, CircuitState> circuitStates = 
        new java.util.concurrent.ConcurrentHashMap<>();
    private static final int CIRCUIT_THRESHOLD = 5;
    private static final long CIRCUIT_TIMEOUT_MS = 30000;
    
    public enum CircuitState {
        CLOSED, OPEN, HALF_OPEN
    }
    
    /**
     * Execute an operation with retry logic.
     */
    public <T> T executeWithRetry(String operationId, java.util.function.Supplier<T> operation) 
            throws DesktopException {
        return executeWithRetry(operationId, operation, MAX_RETRIES);
    }
    
    /**
     * Execute an operation with configurable retry logic.
     */
    public <T> T executeWithRetry(String operationId, java.util.function.Supplier<T> operation, 
                                   int maxRetries) throws DesktopException {
        if (isCircuitOpen(operationId)) {
            throw new DesktopException(
                DesktopException.ErrorCode.NETWORK_ERROR,
                "Service temporarily unavailable. Please try again later."
            );
        }
        
        Exception lastException = null;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                T result = operation.get();
                recordSuccess(operationId);
                return result;
            } catch (Exception e) {
                lastException = e;
                LOG.warn("Operation {} failed (attempt {}/{}): {}", 
                    operationId, attempt, maxRetries, e.getMessage());
                
                if (attempt < maxRetries) {
                    try {
                        Thread.sleep(RETRY_DELAY_MS * attempt); // Exponential backoff
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        
        recordFailure(operationId);
        throw new DesktopException(
            DesktopException.ErrorCode.NETWORK_ERROR,
            "Operation failed after " + maxRetries + " attempts: " + lastException.getMessage(),
            lastException
        );
    }
    
    /**
     * Execute async operation with retry and callback.
     */
    public <T> CompletableFuture<T> executeAsyncWithRetry(
            String operationId, 
            java.util.function.Supplier<T> operation,
            Consumer<T> onSuccess,
            Consumer<Throwable> onError) {
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                return executeWithRetry(operationId, operation);
            } catch (DesktopException e) {
                throw new RuntimeException(e);
            }
        }).whenComplete((result, error) -> {
            Platform.runLater(() -> {
                if (error != null) {
                    LOG.error("Async operation {} failed", operationId, error);
                    onError.accept(error);
                } else {
                    onSuccess.accept(result);
                }
            });
        });
    }
    
    /**
     * Show user-friendly error dialog.
     */
    public void showErrorDialog(String title, String message, boolean retryable) {
        Platform.runLater(() -> {
            Alert.AlertType type = retryable ? Alert.AlertType.WARNING : Alert.AlertType.ERROR;
            Alert alert = new Alert(type);
            alert.setTitle(title);
            alert.setHeaderText(null);
            alert.setContentText(message);
            
            if (retryable) {
                alert.getButtonTypes().add(ButtonType.OK);
            }
            
            alert.showAndWait();
        });
    }
    
    /**
     * Show informational notification.
     */
    public void showInfo(String title, String message) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle(title);
            alert.setHeaderText(null);
            alert.setContentText(message);
            alert.show();
        });
    }
    
    /**
     * Show success notification.
     */
    public void showSuccess(String message) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle("Success");
            alert.setHeaderText(null);
            alert.setContentText(message);
            alert.show();
        });
    }
    
    // Circuit breaker implementation
    
    private boolean isCircuitOpen(String operationId) {
        CircuitState state = circuitStates.get(operationId);
        if (state == null) return false;
        
        if (state == CircuitState.OPEN) {
            // Check if timeout has passed
            Long openTime = circuitOpenTime.get(operationId);
            if (openTime != null && System.currentTimeMillis() - openTime > CIRCUIT_TIMEOUT_MS) {
                circuitStates.put(operationId, CircuitState.HALF_OPEN);
                failureCounts.put(operationId, 0);
                return false;
            }
            return true;
        }
        return false;
    }
    
    private final java.util.concurrent.ConcurrentHashMap<String, Integer> failureCounts = 
        new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String, Long> circuitOpenTime = 
        new java.util.concurrent.ConcurrentHashMap<>();
    
    private void recordSuccess(String operationId) {
        failureCounts.put(operationId, 0);
        circuitStates.put(operationId, CircuitState.CLOSED);
    }
    
    private void recordFailure(String operationId) {
        int failures = failureCounts.merge(operationId, 1, Integer::sum);
        if (failures >= CIRCUIT_THRESHOLD) {
            circuitStates.put(operationId, CircuitState.OPEN);
            circuitOpenTime.put(operationId, System.currentTimeMillis());
            LOG.warn("Circuit breaker OPEN for operation {}", operationId);
        }
    }
    
    /**
     * Reset circuit breaker for an operation.
     */
    public void resetCircuit(String operationId) {
        circuitStates.remove(operationId);
        failureCounts.remove(operationId);
        circuitOpenTime.remove(operationId);
    }
    
    /**
     * Dispose resources.
     */
    public void dispose() {
        circuitStates.clear();
        failureCounts.clear();
        circuitOpenTime.clear();
    }
}
