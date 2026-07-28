package com.eventticketing.reservation.dto;

import com.eventticketing.reservation.domain.BookingSeat;
import com.eventticketing.reservation.domain.BookingSeatStatus;

import java.math.BigDecimal;

public record BookingSeatResponse(
        Long seatId,
        String label,
        Long sectionId,
        String sectionName,
        String currency,
        BigDecimal price,
        BookingSeatStatus status
) {
    public static BookingSeatResponse from(BookingSeat bs) {
        return new BookingSeatResponse(
                bs.getSeat().getId(),
                bs.getSeat().getLabel(),
                bs.getSectionId(),
                bs.getSectionName(),
                bs.getCurrency(),
                bs.getPrice(),
                bs.getStatus()
        );
    }
}
