package com.eventticketing.catalog.dto;

import com.eventticketing.catalog.domain.EventPricing;
import com.eventticketing.catalog.domain.SeatType;

import java.math.BigDecimal;

public record PricingResponse(
        SeatType seatType,
        BigDecimal price
) {
    public static PricingResponse from(EventPricing p) {
        return new PricingResponse(p.getSeatType(), p.getPrice());
    }
}
