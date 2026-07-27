package com.eventticketing.catalog.dto;

import com.eventticketing.catalog.domain.SectionBookingMode;
import com.eventticketing.catalog.domain.SectionShape;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;

/**
 * One section in a full-hall reconcile ({@code PUT /api/halls/{id}/seats}). A null {@code id}
 * creates a new section; existing sections left out of the request are deleted (their seats are
 * detached, never their bookings). Geometry is the raw polygon {@code points}.
 */
public record SectionItem(
        Long id,
        @NotBlank String name,
        @NotNull SectionBookingMode bookingMode,
        @DecimalMin("0.0") BigDecimal defaultPrice,
        String currency,
        /** Required for GENERAL_ADMISSION; ignored (derived from seats) for SEATED. */
        Integer capacity,
        SectionShape shapeKind,
        List<PointItem> points,
        String color
) {
}
