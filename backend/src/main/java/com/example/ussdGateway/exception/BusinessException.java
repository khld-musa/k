package com.example.ussdGateway.exception;

/**
 * Thrown for business logic violations (duplicate, conflict, invalid state).
 * Maps to HTTP 409 in the GlobalExceptionHandler.
 */
public class BusinessException extends RuntimeException {
    public BusinessException(String message) {
        super(message);
    }
}
