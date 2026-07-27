package com.eventticketing.catalog.dto;

import com.eventticketing.catalog.domain.LayoutObjectType;
import com.eventticketing.catalog.domain.TableShape;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * One non-bookable layout object (e.g. a table) in a full-hall reconcile
 * ({@code PUT /api/halls/{id}/seats}). A null {@code id} creates a new object; a non-null id
 * updates the matching one. Existing objects whose id is absent from the request are deleted.
 *
 * <p>Shape-specific dimensions map onto the footprint as: square → {@code layoutWidth} ==
 * {@code layoutDepth} (size); rectangle → {@code layoutWidth} (width) and {@code layoutDepth}
 * (length); circle → {@code layoutWidth} == {@code layoutDepth} (diameter).
 */
public record LayoutObjectItem(
        Long id,
        /** Defaults to {@link LayoutObjectType#TABLE} when omitted. */
        LayoutObjectType objectType,
        /** Required when the object is a TABLE. */
        TableShape shape,
        String label,
        @NotNull @Min(0) Integer layoutX,
        @NotNull @Min(0) Integer layoutY,
        @NotNull @Min(0) @Max(400) Integer layoutZ,
        @NotNull @Min(-180) @Max(180) Integer rotationDegrees,
        @NotNull @Min(20) @Max(600) Integer layoutWidth,
        @NotNull @Min(20) @Max(600) Integer layoutDepth,
        @NotNull @Min(6) @Max(300) Integer objectHeight
) {
}
