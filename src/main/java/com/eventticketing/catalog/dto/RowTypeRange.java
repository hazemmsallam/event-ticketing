package com.eventticketing.catalog.dto;

import com.eventticketing.catalog.domain.SeatType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Assigns a seat type to an inclusive range of 1-based row numbers when creating a
 * seated hall. Rows not covered by any range default to {@link SeatType#REGULAR}.
 */
public record RowTypeRange(
        @Min(1) int fromRow,
        @Min(1) int toRow,
        @NotNull SeatType seatType
) {
}
