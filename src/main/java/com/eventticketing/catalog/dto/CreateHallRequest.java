package com.eventticketing.catalog.dto;

import com.eventticketing.catalog.domain.SeatNumberingScheme;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * Creates a hall. For a seated hall provide {@code numRows}, {@code numColumns} and
 * optionally {@code rowTypes}; capacity is derived as rows * columns. For a non-seated
 * hall provide {@code capacity}.
 */
public record CreateHallRequest(
        @NotBlank String name,
        String address,
        boolean seated,
        Integer numRows,
        Integer numColumns,
        SeatNumberingScheme numberingScheme,
        @Valid List<RowTypeRange> rowTypes,
        Integer capacity
) {
}
