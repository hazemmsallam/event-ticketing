package com.eventticketing.catalog.service;

import com.eventticketing.catalog.dto.PointItem;
import com.eventticketing.common.exception.BusinessRuleException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * Serialises section boundary polygons to/from the JSON stored in {@code section.points} and
 * answers point-in-polygon queries so seats can be assigned to the section that visually contains
 * them. Points are in the hall's layout-pixel space (the same coordinates seats use).
 */
public final class SectionGeometry {

    private SectionGeometry() {
    }

    private static final TypeReference<List<PointItem>> LIST_OF_POINTS = new TypeReference<>() {
    };

    public static String toJson(ObjectMapper mapper, List<PointItem> points) {
        try {
            return mapper.writeValueAsString(points == null ? List.of() : points);
        } catch (Exception e) {
            throw new BusinessRuleException("Invalid section geometry.");
        }
    }

    public static List<PointItem> fromJson(ObjectMapper mapper, String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<PointItem> points = mapper.readValue(json, LIST_OF_POINTS);
            return points != null ? points : List.of();
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * Ray-casting point-in-polygon test. Returns false for degenerate polygons (fewer than 3
     * vertices), so a section with no drawn boundary simply contains no seats.
     */
    public static boolean contains(List<PointItem> polygon, double x, double y) {
        if (polygon == null || polygon.size() < 3) {
            return false;
        }
        boolean inside = false;
        int n = polygon.size();
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double xi = polygon.get(i).x(), yi = polygon.get(i).y();
            double xj = polygon.get(j).x(), yj = polygon.get(j).y();
            boolean intersect = ((yi > y) != (yj > y))
                    && (x < (xj - xi) * (y - yi) / (yj - yi) + xi);
            if (intersect) {
                inside = !inside;
            }
        }
        return inside;
    }
}
