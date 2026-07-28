package com.eventticketing.catalog.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SeatLayoutItem(
        @NotNull Long id,
        @NotNull @Min(0) Integer layoutX,
        @NotNull @Min(0) Integer layoutY,
        @NotNull @Min(-180) @Max(180) Integer rotationDegrees,
        @NotNull @Min(12) @Max(120) Integer layoutWidth,
        @NotNull @Min(12) @Max(120) Integer layoutHeight,
        String sectionName
) {
}
