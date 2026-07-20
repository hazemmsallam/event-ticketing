package com.eventticketing.catalog.dto;

import com.eventticketing.catalog.domain.Hall;
import com.eventticketing.catalog.domain.SeatNumberingScheme;

import java.util.List;

public record HallResponse(
        Long id,
        String name,
        String address,
        int capacity,
        boolean seated,
        Integer numRows,
        Integer numColumns,
        SeatNumberingScheme numberingScheme,
        List<SeatResponse> seats
) {
    public static HallResponse from(Hall h, List<SeatResponse> seats) {
        return new HallResponse(h.getId(), h.getName(), h.getAddress(), h.getCapacity(),
                h.isSeated(), h.getNumRows(), h.getNumColumns(), h.getNumberingScheme(), seats);
    }
}
