package com.eventticketing.catalog.dto;

import com.eventticketing.catalog.domain.Event;
import com.eventticketing.catalog.domain.EventStatus;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Full event detail including hall summary, organizer and pricing.
 */
public record EventResponse(
        Long id,
        String name,
        String description,
        String category,
        String imageUrl,
        String tagline,
        boolean featured,
        Set<String> tags,
        Instant startAt,
        Instant endAt,
        EventStatus status,
        int maxCapacity,
        OrganizerResponse organizer,
        HallSummaryResponse hall,
        List<PricingResponse> pricing
) {
    public static EventResponse from(Event e, List<PricingResponse> pricing) {
        return new EventResponse(
                e.getId(),
                e.getName(),
                e.getDescription(),
                e.getCategory(),
                e.getImageUrl(),
                e.getTagline(),
                e.isFeatured(),
                e.getTags() == null ? new LinkedHashSet<>() : new LinkedHashSet<>(e.getTags()),
                e.getStartAt(),
                e.getEndAt(),
                e.getStatus(),
                e.getMaxCapacity(),
                OrganizerResponse.from(e.getOrganizer()),
                HallSummaryResponse.from(e.getHall()),
                pricing
        );
    }
}
