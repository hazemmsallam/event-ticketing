package com.eventticketing.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Thrown when a requested entity cannot be found. Maps to HTTP 404.
 */
public class ResourceNotFoundException extends ApiException {

    public ResourceNotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, message);
    }

    public static ResourceNotFoundException of(String entity, Object id) {
        return new ResourceNotFoundException("%s not found with id %s".formatted(entity, id));
    }
}
