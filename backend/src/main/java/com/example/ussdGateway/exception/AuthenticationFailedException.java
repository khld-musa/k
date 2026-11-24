package com.example.ussdGateway.exception;

/**
 * Thrown when authentication fails (bad credentials, disabled account, etc).
 * Maps to HTTP 401 in the GlobalExceptionHandler.
 */
public class AuthenticationFailedException extends RuntimeException {
    public AuthenticationFailedException(String message) {
        super(message);
    }
}
