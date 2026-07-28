package com.eventticketing.catalog.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One seat inside a saved preset, positioned relative to the preset's bounding box. Row/seat
 * numbering is deliberately absent: labels belong to the hall a preset is stamped into, so the
 * editor assigns fresh ones. {@code rowOffset} only preserves which seats shared a row, so the
 * stamped block keeps its row structure.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PresetSeatItem(
        int x,
        int y,
        int width,
        int height,
        int rotationDegrees,
        String sectionName,
        Integer rowOffset
) {
}
