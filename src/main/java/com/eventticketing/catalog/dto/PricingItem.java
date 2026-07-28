package com.eventticketing.catalog.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * A price override for one hall section.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PricingItem(
        @NotNull Long sectionId,
        @NotNull @DecimalMin(value = "0.0", inclusive = true) BigDecimal price
) {
}
