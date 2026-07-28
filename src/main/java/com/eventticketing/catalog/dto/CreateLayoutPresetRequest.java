package com.eventticketing.catalog.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Saves the admin's current selection as a reusable preset. {@code width}/{@code height} are the
 * bounding box of the captured block, and every member coordinate in {@code members} is relative to
 * it, so the preset can be stamped anywhere in any hall.
 *
 * <p>{@code name} must be unique across presets — it is how the admin picks one later.
 */
public record CreateLayoutPresetRequest(
        @NotBlank @Size(max = 120) String name,
        @Size(max = 500) String description,
        @NotNull @Min(1) Integer width,
        @NotNull @Min(1) Integer height,
        @NotNull @Valid PresetMembers members
) {
}
