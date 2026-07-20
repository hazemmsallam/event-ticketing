package com.eventticketing.catalog.dto;

import com.eventticketing.catalog.domain.Event;
import com.eventticketing.catalog.domain.EventStatus;

import java.time.Instant;

/**
 * Lightweight event projection for list endpoints (e.g. the mobile "available events" list).
 */
public record EventSummaryResponse(
        Long id,
        String name,
        String category,
        Instant startAt,
        Instant endAt,
        EventStatus status,
        Long hallId,
        String hallName,
        boolean seated
) {
    public static EventSummaryResponse from(Event e) {
        return new EventSummaryResponse(
                e.getId(),
                e.getName(),
                e.getCategory(),
                e.getStartAt(),
                e.getEndAt(),
                e.getStatus(),
                e.getHall().getId(),
                e.getHall().getName(),
                e.getHall().isSeated()
        );
    }
}
