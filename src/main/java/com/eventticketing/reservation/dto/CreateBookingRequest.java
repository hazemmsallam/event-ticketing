package com.eventticketing.reservation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Creates a hold. For a seated event supply {@code seatIds}; for a non-seated (general
 * admission) event supply {@code quantity}. The number of seats/tickets must not exceed the
 * configured {@code app.reservation.max-seats-per-booking}.
 */
public record CreateBookingRequest(
        @NotNull Long eventId,
        @NotBlank String customerRef,
        List<Long> seatIds,
        Integer quantity
) {
}
