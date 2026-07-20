package com.eventticketing.catalog.dto;

import com.eventticketing.catalog.domain.EventStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateEventStatusRequest(
        @NotNull EventStatus status
) {
}
