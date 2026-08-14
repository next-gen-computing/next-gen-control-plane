package com.nextgen.security;

/**
 * Raised when the PKI cannot be read, written or used.
 *
 * <p>Messages name the file involved so a corrupt or unreadable key fails with an actionable error
 * rather than a bare NullPointerException three frames later.
 */
public class PkiException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public PkiException(String message) {
        super(message);
    }

    public PkiException(String message, Throwable cause) {
        super(message, cause);
    }
}
