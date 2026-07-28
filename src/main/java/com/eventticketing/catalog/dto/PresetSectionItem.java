package com.eventticketing.catalog.dto;

import com.eventticketing.catalog.domain.SectionBookingMode;
import com.eventticketing.catalog.domain.SectionShape;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * One section inside a saved preset. Carries the section's own settings (name, booking mode,
 * capacity) plus its boundary, with {@code points} relative to the preset's bounding box. No id: a
 * stamped preset always creates brand-new sections. No price either — a preset is a venue-layout
 * template, and prices are set per event.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PresetSectionItem(
        String name,
        SectionBookingMode bookingMode,
        String currency,
        Integer capacity,
        SectionShape shapeKind,
        List<PointItem> points,
        String color
) {
}
