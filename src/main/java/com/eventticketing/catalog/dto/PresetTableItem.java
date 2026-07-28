package com.eventticketing.catalog.dto;

import com.eventticketing.catalog.domain.LayoutObjectType;
import com.eventticketing.catalog.domain.TableShape;

/**
 * One non-bookable layout object inside a saved preset, positioned relative to its bounding box.
 */
public record PresetTableItem(
        LayoutObjectType objectType,
        TableShape shape,
        String label,
        int x,
        int y,
        int z,
        int rotationDegrees,
        int width,
        int depth,
        int objectHeight
) {
}
