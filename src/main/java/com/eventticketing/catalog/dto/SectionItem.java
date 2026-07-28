package com.eventticketing.catalog.dto;

import com.eventticketing.catalog.domain.SectionBookingMode;
import com.eventticketing.catalog.domain.SectionShape;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * One section in a full-hall reconcile ({@code PUT /api/halls/{id}/seats}). A null {@code id}
 * creates a new section; existing sections left out of the request are deleted (their seats are
 * detached, never their bookings). Geometry is the raw polygon {@code points}.
 *
 * <p>Carries no price: a hall describes the venue, and prices are set per event
 * ({@code PUT /api/events/{id}/pricing}).
 *
 * <p>As a convenience, a client may omit {@code points} and instead send a {@code shapeKind} preset
 * (CIRCLE, ELLIPSE, TRIANGLE, CURVE, RECTANGLE, SQUARE) together with a {@code shapeBox}; the server
 * then generates the boundary polygon (see {@link com.eventticketing.catalog.service.ShapePoints}).
 * Explicit {@code points} always win, so POLYGON/FREEFORM boundaries keep working unchanged.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SectionItem(
        Long id,
        @NotBlank String name,
        @NotNull SectionBookingMode bookingMode,
        String currency,
        /** Required for GENERAL_ADMISSION; ignored (derived from seats) for SEATED. */
        Integer capacity,
        SectionShape shapeKind,
        List<PointItem> points,
        /** Bounding box + rotation used to generate {@code points} from {@code shapeKind} when
         * {@code points} is empty. Ignored when explicit points are supplied. */
        ShapeBox shapeBox,
        String color
) {
}
