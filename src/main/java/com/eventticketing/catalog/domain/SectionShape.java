package com.eventticketing.catalog.domain;

/**
 * Convenience hint describing how a {@link Section}'s boundary was drawn. The authoritative
 * boundary is always the stored polygon points ({@link Section#getPoints()}); this enum only tells
 * the editor which preset (if any) produced them so it can offer the right handles. Free-form and
 * custom boundaries use {@link #POLYGON}/{@link #FREEFORM}. Storing the actual points rather than a
 * per-shape type means new shapes never require new code.
 */
public enum SectionShape {
    RECTANGLE,
    SQUARE,
    CIRCLE,
    ELLIPSE,
    TRIANGLE,
    /** A curved band (half-ring / crescent), e.g. a balcony sweep wrapping a stage. */
    CURVE,
    POLYGON,
    FREEFORM
}
