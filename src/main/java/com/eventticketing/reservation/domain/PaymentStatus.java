package com.eventticketing.reservation.domain;

/**
 * Lifecycle of a payment attempt.
 *
 * <pre>
 *   INITIATED --charge ok, booking confirmed--> SUCCEEDED
 *        |  \--charge declined--> FAILED
 *        \--charged but hold gone--> REFUNDED
 * </pre>
 *
 * INITIATED is the "in-doubt" state the reconciliation job resolves.
 */
public enum PaymentStatus {
    INITIATED,
    SUCCEEDED,
    FAILED,
    REFUNDED
}
