package com.eventticketing.reservation.service;

/**
 * Minimal payment identity handed to the reconciliation job so it can look the charge up at the
 * gateway outside a transaction.
 */
public record PaymentSummary(
        Long paymentId,
        String idempotencyKey
) {
}
