package com.eventticketing.catalog.dto;

import com.eventticketing.catalog.domain.EventPricing;
import java.math.BigDecimal;

public record PricingResponse(
        Long sectionId,
        String sectionName,
        BigDecimal price
) {
    public static PricingResponse from(EventPricing p) {
        return new PricingResponse(
                p.getSection().getId(),
                p.getSection().getName(),
                p.getPrice());
    }
}
