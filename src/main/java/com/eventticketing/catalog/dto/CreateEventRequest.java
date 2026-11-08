package com.eventticketing.catalog.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Set;

public record CreateEventRequest(
        @NotBlank String name,
        String description,
        String category,
        @Size(max = 500) String imageUrl,
        @Size(max = 200) String tagline,
        Boolean featured,
        Set<@Size(max = 60) String> tags,
        @NotNull Instant startAt,
        @NotNull Instant endAt,
        @NotNull Long organizerId,
        @NotNull Long hallId,
        /**
         * Ignored: an event's capacity is derived from its hall (seats plus general-admission
         * capacity). Still accepted so existing clients keep working.
         */
        @Deprecated @Min(1) Integer maxCapacity
) {
}
