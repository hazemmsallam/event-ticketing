package com.eventticketing.reservation.dto;

import com.eventticketing.reservation.domain.SeatAvailabilityStatus;

import java.math.BigDecimal;

/**
 * One seat on the live seat map: identity, section, event price and availability.
 */
public record SeatAvailabilityResponse(
        Long seatId,
        String label,
        String rowLabel,
        int rowIndex,
        int seatNumber,
        Integer layoutX,
        Integer layoutY,
        Integer rotationDegrees,
        Integer layoutWidth,
        Integer layoutHeight,
        String sectionName,
        Long sectionId,
        BigDecimal price,
        SeatAvailabilityStatus status
) {
}
