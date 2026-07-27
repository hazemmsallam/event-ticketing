package com.eventticketing.catalog.dto;

import com.eventticketing.catalog.domain.SeatType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * One seat in a full-hall reconcile ({@code PUT /api/halls/{id}/seats}).
 *
 * <p>{@code id == null} means create a new seat; a non-null id updates that existing seat.
 * Any existing seat whose id is absent from the request is deleted (unless it has bookings).
 */
public record SeatEditItem(
        Long id,
        @NotBlank String label,
        @NotBlank String rowLabel,
        @NotNull @Min(1) Integer rowIndex,
        @NotNull @Min(1) Integer seatNumber,
        /** Legacy seat category; optional now that a seat's price comes from its section. */
        SeatType seatType,
        @NotNull @Min(0) Integer layoutX,
        @NotNull @Min(0) Integer layoutY,
        @NotNull @Min(-180) @Max(180) Integer rotationDegrees,
        @NotNull @Min(12) @Max(120) Integer layoutWidth,
        @NotNull @Min(12) @Max(120) Integer layoutHeight,
        String sectionName
) {
}
