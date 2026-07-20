package com.eventticketing.catalog.service;

/**
 * Converts a 1-based row index into an alphabetical label: 1 -> A, 26 -> Z, 27 -> AA.
 */
final class RowLabels {

    private RowLabels() {
    }

    static String of(int oneBasedIndex) {
        StringBuilder sb = new StringBuilder();
        int n = oneBasedIndex;
        while (n > 0) {
            n--;
            sb.insert(0, (char) ('A' + (n % 26)));
            n /= 26;
        }
        return sb.toString();
    }
}
