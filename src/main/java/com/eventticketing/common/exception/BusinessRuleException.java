package com.eventticketing.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Thrown when a request is well-formed but violates a business rule. Maps to HTTP 422.
 */
public class BusinessRuleException extends ApiException {

    public BusinessRuleException(String message) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }
}
