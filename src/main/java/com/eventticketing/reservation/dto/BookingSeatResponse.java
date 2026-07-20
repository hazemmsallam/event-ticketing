package com.eventticketing.reservation.dto;

import com.eventticketing.catalog.domain.SeatType;
import com.eventticketing.reservation.domain.BookingSeat;
import com.eventticketing.reservation.domain.BookingSeatStatus;

import java.math.BigDecimal;

public record BookingSeatResponse(
        Long seatId,
        String label,
        SeatType seatType,
        BigDecimal price,
        BookingSeatStatus status
) {
    public static BookingSeatResponse from(BookingSeat bs) {
        return new BookingSeatResponse(
                bs.getSeat().getId(),
                bs.getSeat().getLabel(),
                bs.getSeatType(),
                bs.getPrice(),
                bs.getStatus()
        );
    }
}
