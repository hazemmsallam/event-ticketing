package com.eventticketing.reservation.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Creates a hold. For a seated event supply {@code seatIds}; for a general-admission section
 * supply {@code sectionId} and {@code quantity}.
 *
 * <p>The buyer is <strong>not</strong> in this payload. Identity comes from the
 * {@code Authorization} header, because a client-chosen identifier can be rotated per request,
 * which would make the per-user hold quota and rate limit unenforceable.
 *
 * <p>{@link #MAX_SEATS} caps a single request so one call cannot lock a whole venue. The service
 * re-checks against {@code app.reservation.max-seats-per-booking}; this annotation simply rejects
 * an oversized payload at the edge with a 400 before any work is done.
 */
public record CreateBookingRequest(
        @NotNull Long eventId,
        @Size(max = MAX_SEATS, message = "You can hold at most " + MAX_SEATS + " seats per booking.")
        List<Long> seatIds,
        /** For a general-admission section booking: the section to buy tickets in. */
        Long sectionId,
        @Min(value = 1, message = "Quantity must be at least 1.")
        @Max(value = MAX_SEATS, message = "You can hold at most " + MAX_SEATS + " tickets per booking.")
        Integer quantity
) {
    /** Hard ceiling on a single hold, for both seated selections and GA quantities. */
    public static final int MAX_SEATS = 10;
}
