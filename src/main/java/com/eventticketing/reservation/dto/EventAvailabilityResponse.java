package com.eventticketing.reservation.dto;

import java.math.BigDecimal;

/**
 * Live availability for a non-seated (general admission) event.
 */
public record EventAvailabilityResponse(
        Long eventId,
        boolean seated,
        int capacity,
        long confirmed,
        long reserved,
        long available,
        boolean soldOut,
        BigDecimal price
) {
}
