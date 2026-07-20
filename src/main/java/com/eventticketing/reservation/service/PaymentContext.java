package com.eventticketing.reservation.service;

import java.math.BigDecimal;

/**
 * Immutable snapshot captured when a payment begins, so the gateway can be charged outside any
 * database transaction.
 */
public record PaymentContext(
        Long paymentId,
        Long bookingId,
        String customerRef,
        BigDecimal amount,
        String idempotencyKey
) {
}
