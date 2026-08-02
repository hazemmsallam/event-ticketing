package com.eventticketing.common.security;

import java.util.UUID;

/**
 * The authenticated caller, resolved from the {@code Authorization} header and never from the
 * request body. Every quota and rate limit keys off this value, so it has to be something the
 * client cannot choose: a self-asserted identifier can be rotated at will, which makes per-user
 * limits unenforceable.
 *
 * @param value stable UUID for this caller
 */
public record CustomerId(UUID value) {

    /** The form persisted on {@code booking.customer_ref} and matched on for ownership checks. */
    public String ref() {
        return value.toString();
    }

    @Override
    public String toString() {
        return ref();
    }
}
