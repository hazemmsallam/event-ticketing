package com.eventticketing.catalog.dto;

import com.eventticketing.catalog.domain.EventPricing;
import com.eventticketing.catalog.domain.SeatType;

import java.math.BigDecimal;

public record PricingResponse(
        SeatType seatType,
        Long sectionId,
        String sectionName,
        BigDecimal price
) {
    public static PricingResponse from(EventPricing p) {
        return new PricingResponse(
                p.getSeatType(),
                p.getSection() != null ? p.getSection().getId() : null,
                p.getSection() != null ? p.getSection().getName() : null,
                p.getPrice());
    }
}
