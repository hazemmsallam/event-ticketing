package com.eventticketing.catalog.dto;

import com.eventticketing.catalog.domain.LayoutPreset;

import java.time.Instant;

/**
 * A saved preset without its members — enough to render the picker list. Member counts let the UI
 * describe a preset ("2 sections, 12 seats") without shipping every point of every polygon.
 */
public record LayoutPresetSummaryResponse(
        Long id,
        String name,
        String description,
        Integer width,
        Integer height,
        int sectionCount,
        int seatCount,
        int tableCount,
        Instant createdAt
) {
    public static LayoutPresetSummaryResponse from(LayoutPreset p, PresetMembers members) {
        return new LayoutPresetSummaryResponse(p.getId(), p.getName(), p.getDescription(),
                p.getWidth(), p.getHeight(),
                members.sections().size(), members.seats().size(), members.tables().size(),
                p.getCreatedAt());
    }
}
