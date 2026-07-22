package com.eventticketing.reservation.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * Replaces the seats held by a pending booking. The resulting number of seats must still be
 * within {@code app.reservation.max-seats-per-booking}.
 */
public record ChangeSeatsRequest(
        @NotEmpty List<Long> seatIds
) {
}
