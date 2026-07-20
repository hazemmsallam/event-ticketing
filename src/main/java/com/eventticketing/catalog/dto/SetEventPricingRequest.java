package com.eventticketing.catalog.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record SetEventPricingRequest(
        @NotEmpty @Valid List<PricingItem> prices
) {
}
