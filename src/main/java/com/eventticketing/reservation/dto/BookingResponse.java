package com.eventticketing.reservation.dto;

import com.eventticketing.reservation.domain.Booking;
import com.eventticketing.reservation.domain.BookingStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record BookingResponse(
        Long id,
        Long eventId,
        String customerRef,
        BookingStatus status,
        int quantity,
        BigDecimal totalAmount,
        Instant expiresAt,
        Instant confirmedAt,
        String paymentRef,
        List<BookingSeatResponse> seats
) {
    public static BookingResponse from(Booking b) {
        List<BookingSeatResponse> seats = b.getBookingSeats().stream()
                .map(BookingSeatResponse::from)
                .toList();
        return new BookingResponse(
                b.getId(),
                b.getEvent().getId(),
                b.getCustomerRef(),
                b.getStatus(),
                b.getQuantity(),
                b.getTotalAmount(),
                b.getExpiresAt(),
                b.getConfirmedAt(),
                b.getPaymentRef(),
                seats
        );
    }
}
