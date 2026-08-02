package com.eventticketing.reservation.domain;

import com.eventticketing.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Durable record of the payment for a booking, and of which attempt is currently in flight.
 *
 * <p>The {@link #idempotencyKey} is scoped to an <em>attempt</em>, not to the booking. That
 * distinction is load-bearing: a payment provider treats an idempotency key as "replay the stored
 * response", so a key that never changes would make a genuine retry — a customer switching to a
 * working card — replay the original decline instead of charging the new card. The key therefore
 * carries {@link #attempt}, which advances only when the previous attempt reached a terminal
 * state.
 *
 * <p>Advancing the key while an attempt is still {@code INITIATED} would be worse than the
 * original bug: the old key would be lost, and a charge that succeeded at the gateway but was
 * never recorded could no longer be found by reconciliation — money taken with nothing to match
 * it against. Hence the rule enforced in {@code beginPayment}: never re-key an in-doubt payment.
 */
@Entity
@Table(name = "payment")
@Getter
@Setter
@NoArgsConstructor
public class Payment extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "booking_id", nullable = false, unique = true)
    private Booking booking;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 80)
    private String idempotencyKey;

    /** 1-based attempt counter; each value produces a distinct {@link #idempotencyKey}. */
    @Column(name = "attempt", nullable = false)
    private int attempt;

    @Column(name = "customer_ref", nullable = false)
    private String customerRef;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PaymentStatus status;

    /**
     * The provider's own identifier for the charge. Recorded as soon as the charge is issued —
     * not only on success — so reconciliation can retrieve the charge by the provider's id even
     * after the provider has expired our idempotency key (they are typically kept for ~24h).
     */
    @Column(name = "reference", length = 64)
    private String reference;

    @Column(name = "failure_reason", length = 255)
    private String failureReason;

    /** How many times reconciliation has failed to resolve this payment. */
    @Column(name = "reconcile_attempts", nullable = false)
    private int reconcileAttempts;

    /** When reconciliation last tried — drives exponential backoff between attempts. */
    @Column(name = "last_reconcile_at")
    private Instant lastReconcileAt;

    /** Why the last reconciliation attempt failed; the first thing an operator reads. */
    @Column(name = "last_reconcile_error", length = 500)
    private String lastReconcileError;
}
