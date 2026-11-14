package com.eventticketing.catalog.service;

import com.eventticketing.catalog.dto.PointItem;

import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.util.List;

/**
 * Point-in-polygon queries over section boundaries, so seats can be assigned to the section that
 * visually contains them. Points are in the hall's layout-pixel space (the same coordinates seats
 * use).
 */
public final class SectionGeometry {

    private SectionGeometry() {
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

    /** Checks the complete rotated footprint, including any concave cutouts. */
    static boolean containsSeat(List<PointItem> points, double x, double y,
                                double width, double height, double rotation) {
        if (points == null || points.size() < 3) {
            return false;
        }
        var transform = AffineTransform.getRotateInstance(Math.toRadians(rotation),
                x + width / 2, y + height / 2);
        var outside = new Area(transform.createTransformedShape(new Rectangle2D.Double(x, y, width, height)));
        outside.subtract(sectionArea(points));
        return outside.isEmpty();
    }

    static Area sectionArea(List<PointItem> points) {
        if (points.size() < 3) {
            return new Area();
        }
        Path2D boundary = new Path2D.Double(Path2D.WIND_EVEN_ODD);
        boundary.moveTo(points.getFirst().x(), points.getFirst().y());
        for (int i = 1; i < points.size(); i++) {
            boundary.lineTo(points.get(i).x(), points.get(i).y());
        }
        boundary.closePath();

        return new Area(boundary);
    }

    /** Absolute polygon area in layout pixels, used to prefer a specific nested section. */
    public static double area(List<PointItem> polygon) {
        if (polygon == null || polygon.size() < 3) {
            return Double.POSITIVE_INFINITY;
        }
        double sum = 0;
        for (int i = 0, j = polygon.size() - 1; i < polygon.size(); j = i++) {
            PointItem current = polygon.get(i);
            PointItem previous = polygon.get(j);
            sum += previous.x() * current.y() - current.x() * previous.y();
        }
        return Math.abs(sum / 2.0);
    }
}
