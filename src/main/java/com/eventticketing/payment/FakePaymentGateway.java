package com.eventticketing.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Test/demo payment gateway that always approves the charge and returns a fake reference.
 * Keeps an in-memory ledger keyed by idempotency key so {@link #charge} is idempotent and
 * {@link #lookup} can report a prior charge — mirroring how a real provider behaves. Replace
 * with a real gateway implementation for production.
 */
@Component
public class FakePaymentGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(FakePaymentGateway.class);

    /** idempotencyKey -> gateway reference. */
    private final Map<String, String> ledger = new ConcurrentHashMap<>();

    @Override
    public PaymentResult charge(String customerRef, BigDecimal amount, String idempotencyKey) {
        String reference = ledger.computeIfAbsent(idempotencyKey, key -> "PAY-" + UUID.randomUUID());
        log.info("Fake payment approved: customer={}, amount={}, key={}, ref={}",
                customerRef, amount, idempotencyKey, reference);
        return PaymentResult.ok(reference);
    }

    @Override
    public Optional<PaymentResult> lookup(String idempotencyKey) {
        return Optional.ofNullable(ledger.get(idempotencyKey)).map(PaymentResult::ok);
    }

    @Override
    public void refund(String reference) {
        ledger.values().removeIf(ref -> ref.equals(reference));
        log.info("Fake refund issued for ref={}", reference);
    }
}
