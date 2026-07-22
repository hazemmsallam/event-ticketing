package com.eventticketing.catalog.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record UpdateSeatLayoutRequest(
        @NotNull @Min(320) Integer layoutWidth,
        @NotNull @Min(240) Integer layoutHeight,
        @NotEmpty @Valid List<SeatLayoutItem> seats
) {
}
