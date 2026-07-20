package com.eventticketing.catalog.domain;

/**
 * Lifecycle state of an event.
 *
 * <pre>
 *   DRAFT ---> PUBLISHED ---> SOLD_OUT
 *     |            |
 *     +------------+---> CANCELLED
 * </pre>
 *
 * Only PUBLISHED (and SOLD_OUT, which is still visible) events are surfaced to the
 * mobile app; only PUBLISHED events accept new bookings.
 */
public enum EventStatus {
    DRAFT,
    PUBLISHED,
    CANCELLED,
    SOLD_OUT
}
