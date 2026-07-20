package com.eventticketing.reservation.domain;

/**
 * State of a single held seat within a booking. Only HELD and BOOKED are "active" and
 * participate in the {@code active_lock} unique constraint that prevents double-booking.
 */
public enum BookingSeatStatus {
    HELD,
    BOOKED,
    RELEASED
}
