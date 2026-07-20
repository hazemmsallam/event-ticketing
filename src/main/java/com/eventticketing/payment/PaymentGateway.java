package com.eventticketing.payment;

import java.math.BigDecimal;

/**
 * Abstraction over a payment provider. Swap the implementation to integrate a real gateway;
 * the reservation flow depends only on this contract.
 */
public interface PaymentGateway {

    /**
     * Attempts to charge the customer.
     *
     * @param customerRef    buyer reference
     * @param amount         amount to charge
     * @param idempotencyKey stable key (e.g. "booking-{id}") so retries don't double-charge
     */
    PaymentResult charge(String customerRef, BigDecimal amount, String idempotencyKey);
}
