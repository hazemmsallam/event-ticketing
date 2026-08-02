package com.eventticketing.common.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Injects the authenticated {@link CustomerId} into a controller method. Resolved from the
 * {@code Authorization} header by {@link CurrentCustomerArgumentResolver}; a missing or malformed
 * header fails the request with 401 before the handler runs.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentCustomer {
}
