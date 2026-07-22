package com.eventticketing.reservation.service;

import com.eventticketing.common.exception.BusinessRuleException;
import com.eventticketing.payment.PaymentGateway;
import com.eventticketing.payment.PaymentResult;
import com.eventticketing.reservation.dto.PaymentResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Orchestrates payment across two short transactions with the external charge in between, so a
 * database transaction is never held open across the network call to the gateway:
 *
 * <ol>
 *   <li>{@code beginPayment} (txn): validate the hold, persist a Payment as INITIATED.</li>
 *   <li>{@code charge} (no txn): call the gateway with the booking's idempotency key.</li>
 *   <li>{@code applyPaymentResult} (txn): record the outcome and confirm the booking.</li>
 * </ol>
 *
 * If step 3 never runs (crash, DB failure) the Payment stays INITIATED and the reconciliation
 * job later confirms or refunds it — the charge is never silently lost.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final ReservationService reservationService;
    private final PaymentGateway paymentGateway;

    public PaymentService(ReservationService reservationService, PaymentGateway paymentGateway) {
        this.reservationService = reservationService;
        this.paymentGateway = paymentGateway;
    }

    public PaymentResponse pay(Long bookingId, String customerRef) {
        PaymentContext ctx = reservationService.beginPayment(bookingId, customerRef);

        PaymentResult result;
        try {
            result = paymentGateway.charge(ctx.customerRef(), ctx.amount(), ctx.idempotencyKey());
        } catch (RuntimeException ex) {
            // Charge outcome unknown; leave the payment INITIATED for reconciliation to resolve.
            log.warn("Charge call failed for payment {} (booking {}): {}",
                    ctx.paymentId(), bookingId, ex.getMessage());
            throw new BusinessRuleException(
                    "Payment is still processing. Please check the booking status shortly.");
        }

        return reservationService.applyPaymentResult(ctx.paymentId(), result);
    }
}
