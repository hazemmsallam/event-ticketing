package com.eventticketing.catalog.domain;

/**
 * Kind of object placed in a hall's 3D layout.
 *
 * <p>The layout distinguishes <em>bookable</em> seating from <em>non-bookable</em> decoration.
 * Bookable seats are modelled by {@link Seat}; every value in this enum is a non-bookable layout
 * object stored in {@link LayoutObject}. Keeping the two apart means the booking, availability and
 * capacity logic only ever sees seats, and new decoration objects (stages, pillars, screens, …)
 * can be added here later without touching any of that logic.
 */
public enum LayoutObjectType {
    /** A physical table: visualisation only, never bookable and never counted toward capacity. */
    TABLE
}
