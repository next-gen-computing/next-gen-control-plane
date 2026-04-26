package com.nextgen.desktop.exception;

/**
 * Base exception for all desktop application errors.
 * Provides error codes and user-friendly messages.
 */
public class DesktopException extends Exception {
    
    private final ErrorCode errorCode;
    private final String userMessage;
    private final boolean retryable;
    
    public enum ErrorCode {
        SERVER_START_FAILED("E001", "Failed to start server", true),
        NODE_CONNECTION_FAILED("E002", "Failed to connect to server", true),
        CONFIGURATION_INVALID("E003", "Invalid configuration", false),
        API_CALL_FAILED("E004", "API request failed", true),
        METRICS_COLLECTION_FAILED("E005", "Failed to collect metrics", false),
        STORAGE_ERROR("E006", "Data storage error", true),
        VALIDATION_ERROR("E007", "Validation failed", false),
        NETWORK_ERROR("E008", "Network error", true),
        UNKNOWN_ERROR("E999", "Unknown error", false);
        
        private final String code;
        private final String defaultMessage;
        private final boolean retryable;
        
        ErrorCode(String code, String defaultMessage, boolean retryable) {
            this.code = code;
            this.defaultMessage = defaultMessage;
            this.retryable = retryable;
        }
        
        public String getCode() { return code; }
        public String getDefaultMessage() { return defaultMessage; }
        public boolean isRetryable() { return retryable; }
    }
    
    public DesktopException(ErrorCode errorCode) {
        super(errorCode.getDefaultMessage());
        this.errorCode = errorCode;
        this.userMessage = errorCode.getDefaultMessage();
        this.retryable = errorCode.isRetryable();
    }
    
    public DesktopException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
        this.userMessage = message;
        this.retryable = errorCode.isRetryable();
    }
    
    public DesktopException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.userMessage = message;
        this.retryable = errorCode.isRetryable();
    }
    
    public DesktopException(ErrorCode errorCode, Throwable cause) {
        super(errorCode.getDefaultMessage(), cause);
        this.errorCode = errorCode;
        this.userMessage = errorCode.getDefaultMessage();
        this.retryable = errorCode.isRetryable();
    }
    
    public ErrorCode getErrorCode() { return errorCode; }
    public String getUserMessage() { return userMessage; }
    public boolean isRetryable() { return retryable; }
    public String getErrorCodeString() { return errorCode.getCode(); }
}
