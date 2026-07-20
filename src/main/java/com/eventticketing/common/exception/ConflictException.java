package com.eventticketing.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Thrown when an operation conflicts with current state, e.g. a seat is already
 * held or booked by someone else. Maps to HTTP 409.
 */
public class ConflictException extends ApiException {

    public ConflictException(String message) {
        super(HttpStatus.CONFLICT, message);
    }
}
