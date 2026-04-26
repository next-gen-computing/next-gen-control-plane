package com.nextgen.desktop.exception;

/**
 * Exception thrown when the ControlPlane server fails to start.
 */
public class ServerStartException extends DesktopException {
    
    public ServerStartException(String message) {
        super(ErrorCode.SERVER_START_FAILED, message);
    }
    
    public ServerStartException(String message, Throwable cause) {
        super(ErrorCode.SERVER_START_FAILED, message, cause);
    }
}
