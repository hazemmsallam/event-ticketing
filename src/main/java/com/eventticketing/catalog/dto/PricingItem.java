package com.eventticketing.catalog.dto;

import com.eventticketing.catalog.domain.SeatType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * A single price line. {@code seatType} is null for general-admission (non-seated) events.
 */
public record PricingItem(
        SeatType seatType,
        /** Preferred: the section this price applies to. */
        Long sectionId,
        @NotNull @DecimalMin(value = "0.0", inclusive = true) BigDecimal price
) {
}
