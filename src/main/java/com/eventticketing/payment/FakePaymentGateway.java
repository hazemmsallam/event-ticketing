package com.eventticketing.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Test/demo payment gateway that always approves the charge and returns a fake reference.
 * Replace with a real gateway implementation for production.
 */
@Component
public class FakePaymentGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(FakePaymentGateway.class);

    @Override
    public PaymentResult charge(String customerRef, BigDecimal amount, String idempotencyKey) {
        String reference = "PAY-" + UUID.randomUUID();
        log.info("Fake payment approved: customer={}, amount={}, key={}, ref={}",
                customerRef, amount, idempotencyKey, reference);
        return PaymentResult.ok(reference);
    }
}
