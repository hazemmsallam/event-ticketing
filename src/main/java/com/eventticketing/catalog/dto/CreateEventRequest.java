package com.eventticketing.catalog.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public record CreateEventRequest(
        @NotBlank String name,
        String description,
        String category,
        @NotNull Instant startAt,
        @NotNull Instant endAt,
        @NotNull Long organizerId,
        @NotNull Long hallId,
        @NotNull @Min(1) Integer maxCapacity
) {
}
