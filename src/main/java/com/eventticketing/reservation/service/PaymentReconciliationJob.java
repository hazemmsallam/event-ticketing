package com.eventticketing.reservation.service;

import com.eventticketing.common.scheduling.SchedulerLease;
import com.eventticketing.payment.PaymentGateway;
import com.eventticketing.payment.PaymentResult;
import com.eventticketing.reservation.config.ReservationProperties;
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
 *
 * <p>Runs on one instance per tick via a Redis lease. The lease is an optimisation, not the
 * safety mechanism: a refund is claimed in the database ({@code REFUND_PENDING}) before the
 * provider is called, so even overlapping runs cannot refund the same charge twice.
 */
@Component
public class PaymentReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(PaymentReconciliationJob.class);

    private static final String JOB = "payment-reconciler";

    private final ReservationService reservationService;
    private final PaymentGateway paymentGateway;
    private final SchedulerLease lease;
    private final ReservationProperties properties;

    public PaymentReconciliationJob(ReservationService reservationService, PaymentGateway paymentGateway,
                                    SchedulerLease lease, ReservationProperties properties) {
        this.reservationService = reservationService;
        this.paymentGateway = paymentGateway;
        this.lease = lease;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${app.reservation.sweep-interval}")
    public void reconcile() {
        if (!lease.acquire(JOB, properties.sweepInterval().minusSeconds(1))) {
            return;
        }
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
                // Record the failure in its own transaction: the attempt that threw has already
                // rolled back, which is exactly why the row's updated_at never moved and it would
                // otherwise be retried on every tick forever. This applies backoff and, after
                // enough failures, dead-letters it so it stops blocking the queue.
                log.warn("Reconciliation failed for payment {}: {}", summary.paymentId(), ex.getMessage());
                try {
                    reservationService.recordReconcileFailure(summary.paymentId(), ex.getMessage());
                } catch (RuntimeException bookkeepingFailure) {
                    log.error("Could not record reconciliation failure for payment {}: {}",
                            summary.paymentId(), bookkeepingFailure.getMessage());
                }
            }
        }
    }
}
