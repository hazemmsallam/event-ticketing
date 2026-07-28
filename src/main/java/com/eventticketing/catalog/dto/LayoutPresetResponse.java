package com.eventticketing.catalog.dto;

import com.eventticketing.catalog.domain.LayoutPreset;

import java.time.Instant;

/**
 * A saved preset with its full contents — what a client needs to stamp it onto a hall.
 */
public record LayoutPresetResponse(
        Long id,
        String name,
        String description,
        Integer width,
        Integer height,
        PresetMembers members,
        Instant createdAt
) {
    public static LayoutPresetResponse from(LayoutPreset p, PresetMembers members) {
        return new LayoutPresetResponse(p.getId(), p.getName(), p.getDescription(),
                p.getWidth(), p.getHeight(), members, p.getCreatedAt());
    }
}
