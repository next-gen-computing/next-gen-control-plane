package com.nextgen.desktop.viewmodel;

import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import com.nextgen.desktop.exception.DesktopException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Base ViewModel class with common reactive properties and threading support.
 * All ViewModels extend this for consistent state management.
 */
public abstract class BaseViewModel {
    protected final Logger LOG = LoggerFactory.getLogger(getClass());
    
    // State properties
    private final BooleanProperty loading = new SimpleBooleanProperty(false);
    private final StringProperty errorMessage = new SimpleStringProperty("");
    private final BooleanProperty hasError = new SimpleBooleanProperty(false);
    private final StringProperty successMessage = new SimpleStringProperty("");
    private final BooleanProperty hasSuccess = new SimpleBooleanProperty(false);
    private final ObservableList<String> notifications = FXCollections.observableArrayList();
    
    // Thread pool for async operations
    protected final ExecutorService executor;
    private boolean disposed = false;
    
    public BaseViewModel() {
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, getClass().getSimpleName() + "-thread");
            t.setDaemon(true);
            return t;
        });
    }
    
    // Property accessors
    public BooleanProperty loadingProperty() { return loading; }
    public boolean isLoading() { return loading.get(); }
    public void setLoading(boolean value) { loading.set(value); }
    
    public StringProperty errorMessageProperty() { return errorMessage; }
    public String getErrorMessage() { return errorMessage.get(); }
    public void setErrorMessage(String message) {
        errorMessage.set(message != null ? message : "");
        hasError.set(message != null && !message.isEmpty());
    }
    public void clearError() {
        errorMessage.set("");
        hasError.set(false);
    }
    
    public BooleanProperty hasErrorProperty() { return hasError; }
    public boolean hasError() { return hasError.get(); }
    
    public StringProperty successMessageProperty() { return successMessage; }
    public String getSuccessMessage() { return successMessage.get(); }
    public void setSuccessMessage(String message) {
        successMessage.set(message != null ? message : "");
        hasSuccess.set(message != null && !message.isEmpty());
    }
    public void clearSuccess() {
        successMessage.set("");
        hasSuccess.set(false);
    }
    
    public BooleanProperty hasSuccessProperty() { return hasSuccess; }
    public boolean hasSuccess() { return hasSuccess.get(); }
    
    public ObservableList<String> getNotifications() { return notifications; }
    public void addNotification(String message) {
        notifications.add(message);
        // Keep only last 100 notifications
        if (notifications.size() > 100) {
            notifications.remove(0);
        }
    }
    public void clearNotifications() { notifications.clear(); }
    
    /**
     * Execute an async operation with proper loading state management.
     */
    protected void executeAsync(Runnable task, String loadingMessage) {
        if (disposed) {
            LOG.warn("ViewModel is disposed, ignoring async task");
            return;
        }
        
        setLoading(true);
        clearError();
        clearSuccess();
        
        executor.submit(() -> {
            try {
                task.run();
            } catch (Exception e) {
                LOG.error("Async operation failed", e);
                javafx.application.Platform.runLater(() -> {
                    setErrorMessage(e.getMessage() != null ? e.getMessage() : "Operation failed");
                });
            } finally {
                javafx.application.Platform.runLater(() -> setLoading(false));
            }
        });
    }
    
    /**
     * Execute an async operation with error handling and custom error message.
     */
    protected void executeAsync(Runnable task, String loadingMessage, 
                                 java.util.function.Consumer<Throwable> errorHandler) {
        if (disposed) {
            LOG.warn("ViewModel is disposed, ignoring async task");
            return;
        }
        
        setLoading(true);
        clearError();
        clearSuccess();
        
        executor.submit(() -> {
            try {
                task.run();
            } catch (Exception e) {
                LOG.error("Async operation failed", e);
                javafx.application.Platform.runLater(() -> {
                    errorHandler.accept(e);
                    setLoading(false);
                });
            } finally {
                javafx.application.Platform.runLater(() -> setLoading(false));
            }
        });
    }
    
    /**
     * Handle a DesktopException with proper user feedback.
     */
    protected void handleException(DesktopException e) {
        LOG.error("DesktopException [{}]: {}", e.getErrorCodeString(), e.getUserMessage(), e);
        javafx.application.Platform.runLater(() -> {
            setErrorMessage(e.getUserMessage());
            addNotification("Error [" + e.getErrorCodeString() + "]: " + e.getUserMessage());
        });
    }
    
    /**
     * Handle any exception with proper user feedback.
     */
    protected void handleException(Exception e) {
        LOG.error("Unexpected exception: {}", e.getMessage(), e);
        javafx.application.Platform.runLater(() -> {
            setErrorMessage(e.getMessage() != null ? e.getMessage() : "An unexpected error occurred");
            addNotification("Error: " + e.getMessage());
        });
    }
    
    /**
     * Dispose of resources when the ViewModel is no longer needed.
     */
    public void dispose() {
        if (!disposed) {
            disposed = true;
            executor.shutdown();
            LOG.info("ViewModel {} disposed", getClass().getSimpleName());
        }
    }
    
    public boolean isDisposed() { return disposed; }
    
    /**
     * Reset all state properties to defaults.
     */
    public void reset() {
        setLoading(false);
        clearError();
        clearSuccess();
        clearNotifications();
    }
}
