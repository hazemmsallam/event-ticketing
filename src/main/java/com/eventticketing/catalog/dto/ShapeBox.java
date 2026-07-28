package com.eventticketing.catalog.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * An axis-aligned bounding box (top-left corner plus size) with an optional clockwise rotation,
 * used to generate a section's boundary polygon from a {@link com.eventticketing.catalog.domain.SectionShape}
 * preset when explicit points are not supplied. Coordinates are in the hall's layout-pixel space,
 * the same one seats and tables use.
 */
public record ShapeBox(
        @NotNull @Min(0) Integer x,
        @NotNull @Min(0) Integer y,
        @NotNull @Min(1) Integer width,
        @NotNull @Min(1) Integer height,
        /** Clockwise rotation in degrees about the box centre; defaults to 0 when omitted. */
        Integer rotationDegrees
) {
}
