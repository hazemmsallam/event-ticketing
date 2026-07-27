package com.eventticketing.catalog.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Full desired seat set for a seated hall. The service reconciles it against the current seats:
 * creating new entries, updating existing ones, and deleting those left out.
 */
public record UpdateHallSeatsRequest(
        @NotNull @Min(320) Integer layoutWidth,
        @NotNull @Min(240) Integer layoutHeight,
        @NotEmpty @Valid List<SeatEditItem> seats,
        /**
         * Full desired set of non-bookable layout objects (tables, …). {@code null} leaves the
         * hall's existing objects untouched (older clients that don't manage tables); an empty
         * list clears them; otherwise the service reconciles create/update/delete like seats.
         */
        @Valid List<LayoutObjectItem> layoutObjects,
        /**
         * Full desired set of hall sections. {@code null} leaves existing sections untouched; a
         * list (possibly empty) is reconciled create/update/delete. Seats reference these by
         * {@link SeatEditItem#sectionId()} (matched to a section's id, or a client draft key).
         */
        @Valid List<SectionItem> sections
) {
}
