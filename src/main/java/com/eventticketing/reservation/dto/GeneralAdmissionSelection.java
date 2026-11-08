package com.eventticketing.reservation.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** A number of general-admission tickets in one GA section. */
public record GeneralAdmissionSelection(
        @NotNull Long sectionId,
        @NotNull
        @Min(value = 1, message = "Quantity must be at least 1.")
        @Max(value = CreateBookingRequest.MAX_SEATS,
                message = "You can hold at most " + CreateBookingRequest.MAX_SEATS + " tickets per booking.")
        Integer quantity
) {
}
