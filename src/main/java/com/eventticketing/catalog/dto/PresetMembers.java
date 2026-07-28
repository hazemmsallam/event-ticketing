package com.eventticketing.catalog.dto;

import java.util.List;

/**
 * The contents of a {@link com.eventticketing.catalog.domain.LayoutPreset}: the sections, seats and
 * tables that were selected when it was saved. All coordinates are relative to the preset's own
 * bounding box (top-left = 0,0), so applying it is a translate by the drop point — which is what
 * makes a preset reusable in any hall.
 *
 * <p>A list may be empty (a preset of only sections, or only seats, is perfectly valid) but never
 * all three, which the service rejects.
 */
public record PresetMembers(
        List<PresetSectionItem> sections,
        List<PresetSeatItem> seats,
        List<PresetTableItem> tables
) {
    public PresetMembers {
        sections = sections != null ? sections : List.of();
        seats = seats != null ? seats : List.of();
        tables = tables != null ? tables : List.of();
    }

    public int total() {
        return sections.size() + seats.size() + tables.size();
    }
}
