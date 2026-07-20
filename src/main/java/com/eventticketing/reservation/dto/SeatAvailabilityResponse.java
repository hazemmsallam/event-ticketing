package com.eventticketing.reservation.dto;

import com.eventticketing.catalog.domain.SeatType;
import com.eventticketing.reservation.domain.SeatAvailabilityStatus;

import java.math.BigDecimal;

/**
 * One seat on the live seat map: its identity, type, price for this event, and current
 * availability status (AVAILABLE / RESERVED / BOOKED).
 */
public record SeatAvailabilityResponse(
        Long seatId,
        String label,
        String rowLabel,
        int rowIndex,
        int seatNumber,
        SeatType seatType,
        BigDecimal price,
        SeatAvailabilityStatus status
) {
}
