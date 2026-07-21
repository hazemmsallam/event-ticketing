package com.eventticketing.catalog.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Full desired seat set for a seated hall. The service reconciles it against the current seats:
 * creating new entries, updating existing ones, and deleting those left out.
 */
public record UpdateHallSeatsRequest(
        @NotNull @Min(320) Integer layoutWidth,
        @NotNull @Min(240) Integer layoutHeight,
        @NotEmpty @Valid List<SeatEditItem> seats
) {
}
