package com.eventticketing.payment;

public record PaymentResult(
        boolean success,
        String reference,
        String message
) {
    public static PaymentResult ok(String reference) {
        return new PaymentResult(true, reference, "Payment approved.");
    }

    public static PaymentResult failed(String message) {
        return new PaymentResult(false, null, message);
    }
}
