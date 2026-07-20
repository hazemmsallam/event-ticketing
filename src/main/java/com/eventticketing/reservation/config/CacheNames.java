package com.eventticketing.reservation.config;

/**
 * Names of the Redis caches fronting the read-heavy availability endpoints.
 */
public final class CacheNames {

    public static final String EVENT_SEAT_MAP = "eventSeatMap";
    public static final String EVENT_AVAILABILITY = "eventAvailability";

    private CacheNames() {
    }
}
