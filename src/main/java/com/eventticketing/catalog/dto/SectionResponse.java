package com.eventticketing.catalog.dto;

import com.eventticketing.catalog.domain.Section;
import com.eventticketing.catalog.domain.SectionBookingMode;
import com.eventticketing.catalog.domain.SectionShape;

import java.math.BigDecimal;
import java.util.List;

/**
 * A hall section as returned to admin and customer clients. Carries the dynamic name/price/currency,
 * booking mode, capacity and boundary geometry — everything a client needs to render and price a
 * section without any hardcoded categories.
 */
public record SectionResponse(
        Long id,
        String name,
        SectionBookingMode bookingMode,
        BigDecimal defaultPrice,
        String currency,
        Integer capacity,
        SectionShape shapeKind,
        List<PointItem> points,
        String color
) {
    public static SectionResponse from(Section s, List<PointItem> points) {
        return new SectionResponse(s.getId(), s.getName(), s.getBookingMode(), s.getDefaultPrice(),
                s.getCurrency(), s.getCapacity(), s.getShapeKind(), points, s.getColor());
    }
}
