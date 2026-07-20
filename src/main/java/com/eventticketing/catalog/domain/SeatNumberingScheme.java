package com.eventticketing.catalog.domain;

/**
 * How seats are labelled when generating a seated hall's layout.
 * ALPHA_ROW_NUMERIC_SEAT produces labels like {@code A1, A2, ... B1} where the row is
 * an alphabetical prefix (A, B, ... Z, AA, AB, ...) and the seat is a 1-based number.
 */
public enum SeatNumberingScheme {
    ALPHA_ROW_NUMERIC_SEAT
}
