package com.eventticketing.catalog.dto;

import com.eventticketing.catalog.domain.Event;
import com.eventticketing.catalog.domain.EventPricing;
import com.eventticketing.catalog.domain.EventStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Lightweight event projection for list endpoints (e.g. the mobile "available events" list).
 * Carries just enough content for the Flutter card to render without a second round-trip.
 */
public record EventSummaryResponse(
        Long id,
        String name,
        String category,
        String imageUrl,
        String tagline,
        boolean featured,
        Set<String> tags,
        Instant startAt,
        Instant endAt,
        EventStatus status,
        Long hallId,
        String hallName,
        String hallAddress,
        boolean seated,
        BigDecimal startingPrice
) {
    public static EventSummaryResponse from(Event e) {
        EventPricing cheapest = e.getPricing().stream()
                .min(Comparator.comparing(EventPricing::getPrice))
                .orElse(null);
        return new EventSummaryResponse(
                e.getId(),
                e.getName(),
                e.getCategory(),
                e.getImageUrl(),
                e.getTagline(),
                e.isFeatured(),
                e.getTags() == null ? new LinkedHashSet<>() : new LinkedHashSet<>(e.getTags()),
                e.getStartAt(),
                e.getEndAt(),
                e.getStatus(),
                e.getHall().getId(),
                e.getHall().getName(),
                e.getHall().getAddress(),
                e.getHall().isSeated(),
                cheapest == null ? null : cheapest.getPrice()
        );
    }
}
