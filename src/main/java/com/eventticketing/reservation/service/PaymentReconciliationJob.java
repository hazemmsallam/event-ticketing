package com.eventticketing.reservation.service;

import com.eventticketing.payment.PaymentGateway;
import com.eventticketing.payment.PaymentResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Resolves payments left in the in-doubt INITIATED state — the case where the gateway was
 * charged but the app failed before recording the result. For each stuck payment it asks the
 * gateway what actually happened and then confirms the booking (charge succeeded, hold still
 * valid) or refunds it (charge succeeded, seats gone). Gateway calls happen outside any
 * transaction; the state changes happen inside short ones.
 */
@Component
public class PaymentReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(PaymentReconciliationJob.class);

    private final ReservationService reservationService;
    private final PaymentGateway paymentGateway;

    public PaymentReconciliationJob(ReservationService reservationService, PaymentGateway paymentGateway) {
        this.reservationService = reservationService;
        this.paymentGateway = paymentGateway;
    }

    @Scheduled(fixedDelayString = "${app.reservation.sweep-interval}")
    public void reconcile() {
        for (PaymentSummary summary : reservationService.findPaymentsToReconcile()) {
            try {
                Optional<PaymentResult> lookup = paymentGateway.lookup(summary.idempotencyKey());
                ReconcileOutcome outcome = reservationService.reconcile(summary.paymentId(), lookup);
                if (outcome.refundNeeded()) {
                    paymentGateway.refund(outcome.reference());
                    reservationService.markRefunded(summary.paymentId(), outcome.reference());
                    log.info("Reconciliation refunded payment {} (ref {}).",
                            summary.paymentId(), outcome.reference());
                }
            } catch (RuntimeException ex) {
                log.warn("Reconciliation failed for payment {}: {}", summary.paymentId(), ex.getMessage());
            }
        }
    }
}
