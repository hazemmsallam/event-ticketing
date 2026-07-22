package com.eventticketing.catalog.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Updates a hall's descriptive fields. The seat layout is fixed once the hall is created, so it
 * is not editable here.
 */
public record UpdateHallRequest(
        @NotBlank String name,
        String address
) {
}
