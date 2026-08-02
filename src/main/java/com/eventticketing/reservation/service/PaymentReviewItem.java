package com.eventticketing.reservation.service;

import com.eventticketing.reservation.domain.Payment;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A dead-lettered payment as an operator needs to see it.
 *
 * <p>Deliberately carries the {@code idempotencyKey} and {@code gatewayReference}: those are what
 * someone searches for in the provider's dashboard to establish whether money actually moved. A
 * dead letter that omits them forces a manual hunt through logs.
 */
public record PaymentReviewItem(
        Long paymentId,
        Long bookingId,
        String customerRef,
        BigDecimal amount,
        String idempotencyKey,
        String gatewayReference,
        int attempt,
        int reconcileAttempts,
        String lastError,
        Instant lastAttemptedAt,
        Instant inDoubtSince
) {
    public static PaymentReviewItem from(Payment p) {
        return new PaymentReviewItem(
                p.getId(),
                p.getBooking() != null ? p.getBooking().getId() : null,
                p.getCustomerRef(),
                p.getAmount(),
                p.getIdempotencyKey(),
                p.getReference(),
                p.getAttempt(),
                p.getReconcileAttempts(),
                p.getLastReconcileError(),
                p.getLastReconcileAt(),
                p.getUpdatedAt());
    }
}
