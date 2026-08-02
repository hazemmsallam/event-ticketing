package com.eventticketing.reservation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Tunable reservation policy, bound from {@code app.reservation.*}.
 *
 * @param holdDuration        how long a seat stays reserved before auto-release if unpaid
 * @param maxSeatsPerBooking  maximum seats (or GA tickets) a single booking may hold
 * @param sweepInterval       how often the background sweeper releases expired holds
 * @param cacheTtl            how long availability/seat-map reads are cached in Redis
 * @param reconcileAfter      how long a payment may stay in-doubt before reconciliation acts
 * @param unpaidHoldLimit     how many holds a customer may open without paying, per window
 * @param unpaidHoldWindow    the window over which unpaid holds are counted
 */
@ConfigurationProperties(prefix = "app.reservation")
public record ReservationProperties(
        Duration holdDuration,
        int maxSeatsPerBooking,
        Duration sweepInterval,
        Duration cacheTtl,
        Duration reconcileAfter,
        int unpaidHoldLimit,
        Duration unpaidHoldWindow
) {
}
