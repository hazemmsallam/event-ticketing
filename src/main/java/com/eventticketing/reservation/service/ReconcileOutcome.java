package com.eventticketing.reservation.service;

/**
 * Result of reconciling one in-doubt payment. When {@code refundNeeded} is true the charge
 * succeeded but the seats can no longer be honoured, so the job must refund {@code reference}
 * and then mark the payment refunded.
 */
public record ReconcileOutcome(
        boolean refundNeeded,
        String reference
) {
    static final ReconcileOutcome NONE = new ReconcileOutcome(false, null);

    static ReconcileOutcome refund(String reference) {
        return new ReconcileOutcome(true, reference);
    }
}
