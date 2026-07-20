package com.eventticketing.payment;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Abstraction over a payment provider. Swap the implementation to integrate a real gateway;
 * the reservation flow depends only on this contract.
 */
public interface PaymentGateway {

    /**
     * Attempts to charge the customer. Must be idempotent on {@code idempotencyKey}: calling it
     * again with the same key never double-charges and returns the original outcome.
     *
     * @param customerRef    buyer reference
     * @param amount         amount to charge
     * @param idempotencyKey stable key (e.g. "booking-{id}") so retries don't double-charge
     */
    PaymentResult charge(String customerRef, BigDecimal amount, String idempotencyKey);

    /**
     * Looks up whether a charge for the given idempotency key succeeded. Used by reconciliation
     * to recover when the app failed after charging but before recording the result.
     *
     * @return the successful result if a charge exists, otherwise empty
     */
    Optional<PaymentResult> lookup(String idempotencyKey);

    /**
     * Refunds a previously successful charge. Used as the compensating action when a charge
     * succeeded but the seats can no longer be honoured.
     */
    void refund(String reference);
}
