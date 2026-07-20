package com.eventticketing.common.exception;

import java.time.Instant;
import java.util.List;

/**
 * Uniform error payload returned to clients.
 */
public record ApiError(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        List<FieldViolation> fieldErrors
) {
    public record FieldViolation(String field, String message) {
    }
}
