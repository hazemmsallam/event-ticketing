package com.eventticketing.common.security;

import com.eventticketing.reservation.config.ReservationProperties;
import org.springframework.stereotype.Component;

/**
 * Throttles customers who <em>open holds without paying for them</em>, rather than customers who
 * are simply fast.
 *
 * <p>A flat "N requests per minute" limit punishes the wrong behaviour: a decisive buyer who
 * books, pays, and books again for a friend looks identical to a squatter, while a patient
 * squatter who churns holds just under the limit sails through. The signal that actually
 * distinguishes them is <strong>conversion</strong> — abuse means taking inventory off sale and
 * never buying it.
 *
 * <p>So the tally counts holds, and a successful payment {@link #onPaymentConfirmed clears it}:
 *
 * <ul>
 *   <li><b>Buyer:</b> hold → pay → tally wiped → may hold again immediately, no cooldown.</li>
 *   <li><b>Squatter:</b> hold → abandon → hold → abandon → tally climbs → 429 until the window
 *       rolls over.</li>
 * </ul>
 *
 * <p>Abandonment needs no explicit accounting: a hold that is cancelled or left to expire simply
 * never clears the tally, so it decays on the clock instead. That keeps the hot path to a single
 * Redis {@code INCR} with no extra bookkeeping on the cancel and sweep paths.
 */
@Component
public class UnpaidHoldThrottle {

    private static final String ACTION = "unpaid-hold";

    private final RateLimiter rateLimiter;
    private final ReservationProperties properties;

    public UnpaidHoldThrottle(RateLimiter rateLimiter, ReservationProperties properties) {
        this.rateLimiter = rateLimiter;
        this.properties = properties;
    }

    /**
     * Counts one hold attempt against the customer's unpaid tally.
     *
     * @throws com.eventticketing.common.exception.TooManyRequestsException once the customer has
     *         opened more unpaid holds than the policy allows for this window
     */
    public void recordHoldAttempt(CustomerId customer) {
        rateLimiter.check(ACTION, customer.ref(),
                properties.unpaidHoldLimit(), properties.unpaidHoldWindow());
    }

    /**
     * Wipes the tally: this customer converted, so they have earned a fresh budget and must not
     * be made to wait. Keyed by {@code customer_ref} because it is called from the payment path,
     * which knows the booking rather than the request's authenticated principal.
     */
    public void onPaymentConfirmed(String customerRef) {
        rateLimiter.reset(ACTION, customerRef, properties.unpaidHoldWindow());
    }
}
