package com.eventticketing.reservation.dto;

import com.eventticketing.reservation.domain.Booking;
import com.eventticketing.reservation.domain.BookingSeatStatus;
import com.eventticketing.reservation.domain.BookingStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record BookingResponse(
        Long id,
        Long eventId,
        String eventName,
        Instant eventStartAt,
        String customerRef,
        BookingStatus status,
        int quantity,
        BigDecimal totalAmount,
        Instant createdAt,
        Instant expiresAt,
        Instant confirmedAt,
        String paymentRef,
        List<BookingSeatResponse> seats
) {
    public static BookingResponse from(Booking b) {
        List<BookingSeatResponse> seats = b.getBookingSeats().stream()
                .filter(bs -> bs.getStatus() != BookingSeatStatus.RELEASED)
                .map(BookingSeatResponse::from)
                .toList();
        return new BookingResponse(
                b.getId(),
                b.getEvent().getId(),
                b.getEvent().getName(),
                b.getEvent().getStartAt(),
                b.getCustomerRef(),
                b.getStatus(),
                b.getQuantity(),
                b.getTotalAmount(),
                b.getCreatedAt(),
                b.getExpiresAt(),
                b.getConfirmedAt(),
                b.getPaymentRef(),
                seats
        );
    }
}
