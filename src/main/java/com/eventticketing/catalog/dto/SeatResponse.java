package com.eventticketing.catalog.dto;

import com.eventticketing.catalog.domain.Seat;
import com.eventticketing.catalog.domain.SeatType;

public record SeatResponse(
        Long id,
        String label,
        String rowLabel,
        int rowIndex,
        int seatNumber,
        SeatType seatType
) {
    public static SeatResponse from(Seat s) {
        return new SeatResponse(s.getId(), s.getLabel(), s.getRowLabel(), s.getRowIndex(),
                s.getSeatNumber(), s.getSeatType());
    }
}
