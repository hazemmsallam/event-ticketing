package com.eventticketing.catalog.domain;

/**
 * Footprint shape of a {@link LayoutObjectType#TABLE}. Stored separately from the object type so a
 * table's silhouette is described independently of the fact that it is a table.
 *
 * <ul>
 *   <li>{@link #SQUARE} — a single size drives both footprint sides (width == length).</li>
 *   <li>{@link #RECTANGLE} — independent width and length.</li>
 *   <li>{@link #CIRCLE} — a diameter drives the bounding box (width == length == diameter).</li>
 * </ul>
 */
public enum TableShape {
    SQUARE,
    RECTANGLE,
    CIRCLE
}
