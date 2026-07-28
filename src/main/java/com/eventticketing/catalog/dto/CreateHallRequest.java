package com.eventticketing.catalog.dto;

import com.eventticketing.catalog.domain.SeatNumberingScheme;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;

/**
 * Creates a hall. For a seated hall provide {@code numRows} and {@code numColumns}; capacity is
 * derived as rows * columns. For a non-seated hall provide {@code capacity}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CreateHallRequest(
        @NotBlank String name,
        String address,
        boolean seated,
        Integer numRows,
        Integer numColumns,
        SeatNumberingScheme numberingScheme,
        Integer capacity
) {
}
