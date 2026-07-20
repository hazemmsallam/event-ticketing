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
 */
@ConfigurationProperties(prefix = "app.reservation")
public record ReservationProperties(
        Duration holdDuration,
        int maxSeatsPerBooking,
        Duration sweepInterval,
        Duration cacheTtl
) {
}
