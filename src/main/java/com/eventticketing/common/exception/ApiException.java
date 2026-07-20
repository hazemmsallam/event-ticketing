package com.eventticketing.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Base type for all domain-level exceptions that map to a specific HTTP status.
 */
public abstract class ApiException extends RuntimeException {

    private final HttpStatus status;

    protected ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
