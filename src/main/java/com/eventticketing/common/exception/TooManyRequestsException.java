package com.eventticketing.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Thrown when a caller exceeds a rate limit. Maps to HTTP 429 and carries the number of seconds
 * the client should wait, which the handler emits as a {@code Retry-After} header.
 */
public class TooManyRequestsException extends ApiException {

    private final long retryAfterSeconds;

    public TooManyRequestsException(String message, long retryAfterSeconds) {
        super(HttpStatus.TOO_MANY_REQUESTS, message);
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
