package com.eventticketing.reservation.domain;

/**
 * Lifecycle of a payment attempt.
 *
 * <pre>
 *   INITIATED --charge ok, booking confirmed--&gt; SUCCEEDED
 *        |  \--charge declined--&gt; FAILED
 *        |  \--charged but hold gone--&gt; REFUNDED
 *        \--reconciliation keeps failing--&gt; NEEDS_REVIEW
 * </pre>
 *
 * INITIATED is the "in-doubt" state the reconciliation job resolves.
 */
public enum PaymentStatus {
    INITIATED,
    SUCCEEDED,
    FAILED,
    /**
     * A refund is owed and has been <em>claimed</em> by one worker, but not yet confirmed by the
     * provider.
     *
     * <p>This state exists to make refunding safe on more than one instance. Deciding "a refund is
     * owed" happens in a transaction, but issuing it is a network call outside one — so without a
     * claim, two replicas (or two ticks after a crash between refund and bookkeeping) would both
     * see an unresolved payment and both refund it. Claiming the row first means exactly one
     * worker owns the refund; if it dies mid-way the row stays here and is retried, never
     * duplicated.
     */
    REFUND_PENDING,
    REFUNDED,
    /**
     * Dead letter: reconciliation failed too many times, so the payment is parked for a human.
     *
     * <p>Terminal on purpose — it removes the row from the reconciliation queue, which is what
     * stops a permanently broken record from consuming every tick and delaying the healthy
     * in-doubt payments behind it. The idempotency key, attempt number and gateway reference are
     * all retained so an operator can find the charge at the provider.
     *
     * <p>A row in this state means money may be unaccounted for. It should always be alerted on.
     */
    NEEDS_REVIEW
}
