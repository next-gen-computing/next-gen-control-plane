package com.nextgen.desktop.exception;

/**
 * Exception thrown when connection to ControlPlane server fails.
 */
public class ConnectionException extends DesktopException {
    
    private final String host;
    private final int port;
    private final int retryCount;
    
    public ConnectionException(String host, int port, String message) {
        super(ErrorCode.NODE_CONNECTION_FAILED, message);
        this.host = host;
        this.port = port;
        this.retryCount = 0;
    }
    
    public ConnectionException(String host, int port, int retryCount, Throwable cause) {
        super(ErrorCode.NODE_CONNECTION_FAILED, 
              String.format("Failed to connect to %s:%d after %d attempts", host, port, retryCount), 
              cause);
        this.host = host;
        this.port = port;
        this.retryCount = retryCount;
    }
    
    public String getHost() { return host; }
    public int getPort() { return port; }
    public int getRetryCount() { return retryCount; }
}
