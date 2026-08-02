package com.eventticketing.common.exception;

import org.springframework.http.HttpStatus;

/** Thrown when a request carries no usable credential. Maps to HTTP 401. */
public class UnauthorizedException extends ApiException {

    public UnauthorizedException(String message) {
        super(HttpStatus.UNAUTHORIZED, message);
    }
}
