package com.eventticketing.catalog.dto;

import com.eventticketing.catalog.domain.Hall;

public record HallSummaryResponse(
        Long id,
        String name,
        String address,
        int capacity,
        boolean seated
) {
    public static HallSummaryResponse from(Hall h) {
        return new HallSummaryResponse(h.getId(), h.getName(), h.getAddress(), h.getCapacity(), h.isSeated());
    }
}
