package com.eventticketing.reservation.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Replaces the whole selection of a pending booking. Omitted or empty lists mean "none", so
 * sending no {@code generalAdmission} removes every GA ticket. The same rules as creation apply.
 */
public record ChangeBookingItemsRequest(
        @Size(max = CreateBookingRequest.MAX_SEATS,
                message = "You can hold at most " + CreateBookingRequest.MAX_SEATS + " seats per booking.")
        List<Long> seatIds,
        @Size(max = CreateBookingRequest.MAX_SEATS,
                message = "You can select at most " + CreateBookingRequest.MAX_SEATS + " general-admission sections.")
        List<@Valid @NotNull GeneralAdmissionSelection> generalAdmission
) {
}
