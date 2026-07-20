package com.eventticketing.reservation.dto;

import com.eventticketing.reservation.domain.BookingStatus;

public record PaymentResponse(
        Long bookingId,
        BookingStatus status,
        String paymentRef,
        boolean success,
        String message
) {
}
