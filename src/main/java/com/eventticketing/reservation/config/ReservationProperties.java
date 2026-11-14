package com.eventticketing.reservation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Tunable reservation policy, bound from {@code app.reservation.*}.
 *
 * @param holdDuration        how long a seat stays reserved before it lapses if unpaid
 * @param maxSeatsPerBooking  maximum seats (or GA tickets) a single booking may hold
 * @param reconcileAfter      how long a payment may stay in-doubt before reconciliation acts
 */
@ConfigurationProperties(prefix = "app.reservation")
public record ReservationProperties(
        Duration holdDuration,
        int maxSeatsPerBooking,
        Duration reconcileAfter
) {
}
