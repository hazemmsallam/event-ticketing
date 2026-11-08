package com.eventticketing.reservation.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Creates one hold in an event: seats from seated sections, tickets from general-admission
 * sections, or both. Either list may be empty, but not both; seats plus all GA quantities together
 * must not exceed {@link #MAX_SEATS}.
 *
 * <p>The buyer is <strong>not</strong> in this payload. Identity comes from the
 * {@code Authorization} header, because a client-chosen identifier can be rotated per request,
 * which would make the per-user hold quota and rate limit unenforceable.
 *
 * <p>{@link #MAX_SEATS} caps a single request so one call cannot lock a whole venue. The service
 * re-checks the combined total against {@code app.reservation.max-seats-per-booking}; these
 * annotations simply reject an oversized payload at the edge with a 400 before any work is done.
 */
public record CreateBookingRequest(
        @NotNull Long eventId,
        @Size(max = MAX_SEATS, message = "You can hold at most " + MAX_SEATS + " seats per booking.")
        List<Long> seatIds,
        @Size(max = MAX_SEATS, message = "You can select at most " + MAX_SEATS + " general-admission sections.")
        List<@Valid @NotNull GeneralAdmissionSelection> generalAdmission
) {
    /** Hard ceiling on a single hold: seats and GA tickets combined. */
    public static final int MAX_SEATS = 10;
}
