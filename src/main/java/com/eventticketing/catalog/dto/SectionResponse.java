package com.eventticketing.catalog.dto;

import com.eventticketing.catalog.domain.Section;
import com.eventticketing.catalog.domain.SectionBookingMode;
import com.eventticketing.catalog.domain.SectionShape;

import java.math.BigDecimal;
import java.util.List;

/**
 * A hall section as returned to admin and customer clients: the dynamic name, booking mode,
 * capacity and boundary geometry. No price — that belongs to an event, not the venue; see
 * {@link com.eventticketing.reservation.dto.SectionAvailabilityResponse} for the priced view.
 */
public record SectionResponse(
        Long id,
        String name,
        SectionBookingMode bookingMode,
        Integer capacity,
        SectionShape shapeKind,
        List<PointItem> points,
        String color,
        BigDecimal serviceFeePerTicket
) {
    public static SectionResponse from(Section s) {
        return new SectionResponse(s.getId(), s.getName(), s.getBookingMode(),
                s.getCapacity(), s.getShapeKind(), s.getPoints(), s.getColor(), s.getServiceFeePerTicket());
    }
}
