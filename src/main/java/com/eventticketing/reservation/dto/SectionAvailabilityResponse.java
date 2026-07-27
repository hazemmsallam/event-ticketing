package com.eventticketing.reservation.dto;

import com.eventticketing.catalog.domain.SectionBookingMode;
import com.eventticketing.catalog.domain.SectionShape;
import com.eventticketing.catalog.dto.PointItem;

import java.math.BigDecimal;
import java.util.List;

/**
 * One section on the live event view: its dynamic name/price/currency, booking mode, boundary
 * geometry, and current availability. For SEATED sections capacity/counts are derived from the
 * section's seats; for GENERAL_ADMISSION they reflect the configured capacity minus confirmed and
 * held tickets. Carries no hardcoded categories — the client renders whatever it receives.
 */
public record SectionAvailabilityResponse(
        Long id,
        String name,
        SectionBookingMode bookingMode,
        BigDecimal price,
        String currency,
        int capacity,
        long available,
        long reserved,
        long sold,
        SectionShape shapeKind,
        List<PointItem> points,
        String color
) {
}
