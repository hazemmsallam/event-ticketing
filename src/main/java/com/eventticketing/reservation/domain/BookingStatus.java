package com.eventticketing.reservation.domain;

/**
 * Lifecycle of a booking (order).
 *
 * <pre>
 *   PENDING_PAYMENT --pay--> CONFIRMED
 *        |  \--cancel--> CANCELLED
 *        \--hold expires--> EXPIRED
 * </pre>
 */
public enum BookingStatus {
    PENDING_PAYMENT,
    CONFIRMED,
    EXPIRED,
    CANCELLED
}
