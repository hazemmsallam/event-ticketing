package com.eventticketing.catalog.dto;

import com.eventticketing.catalog.domain.EventPricing;
import com.eventticketing.catalog.domain.SectionBookingMode;
import java.math.BigDecimal;

/**
 * A section the audience can pick on the event detail screen: price plus the
 * presentation hints (colour, booking mode, capacity) the mobile
 * client needs to render a chair card and route a tap into the right flow
 * (seat picker vs quantity sheet).
 */
public record PricingResponse(
        Long sectionId,
        String sectionName,
        BigDecimal price,
        String color,
        SectionBookingMode bookingMode,
        Integer capacity
) {
    public static PricingResponse from(EventPricing p) {
        var s = p.getSection();
        return new PricingResponse(
                s.getId(),
                s.getName(),
                p.getPrice(),
                s.getColor(),
                s.getBookingMode(),
                s.getCapacity());
    }
}
