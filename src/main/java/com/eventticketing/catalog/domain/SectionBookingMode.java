package com.eventticketing.catalog.domain;

/**
 * How a {@link Section} is booked. A single hall may mix both modes across its sections.
 *
 * <ul>
 *   <li>{@link #SEATED} — the section contains individual, selectable chairs; booking is per
 *       {@code seatId} and capacity is derived from the bookable seats.</li>
 *   <li>{@link #GENERAL_ADMISSION} — an open/standing area with no assigned chairs; the customer
 *       picks a quantity and capacity is the admin-configured {@code capacity}.</li>
 * </ul>
 */
public enum SectionBookingMode {
    SEATED,
    GENERAL_ADMISSION
}
