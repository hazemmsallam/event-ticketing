package com.eventticketing.catalog.service;

import com.eventticketing.catalog.domain.SectionShape;
import com.eventticketing.catalog.dto.PointItem;

import java.util.ArrayList;
import java.util.List;

/**
 * Generates a section boundary polygon (a list of {@link PointItem} in layout-pixel space) for a
 * shape preset inscribed in an axis-aligned bounding box, then optionally rotated about the box
 * centre. This lets a client create a circle/triangle/curve section by sending only the preset plus
 * its box, instead of hand-computing every vertex — while the authoritative geometry stays the raw
 * polygon points (see {@link com.eventticketing.catalog.domain.Section}).
 *
 * <p>Curved presets ({@link SectionShape#CIRCLE}, {@link SectionShape#ELLIPSE},
 * {@link SectionShape#CURVE}) are approximated by many short segments, which the point-in-polygon
 * test in {@link SectionGeometry} handles exactly like any other polygon. {@link SectionShape#POLYGON}
 * and {@link SectionShape#FREEFORM} have no preset silhouette, so they generate nothing — their
 * points must be supplied explicitly.
 */
public final class ShapePoints {

    private ShapePoints() {
    }

    /** Number of segments used to approximate a full circle/ellipse outline. */
    private static final int CIRCLE_SEGMENTS = 48;
    /** Number of segments per arc (outer and inner) of a {@link SectionShape#CURVE} band. */
    private static final int CURVE_SEGMENTS = 32;

    /**
     * Builds the polygon for {@code shape} inscribed in the box whose top-left corner is
     * ({@code x},{@code y}) with the given {@code width} and {@code height}, rotated
     * {@code rotationDegrees} clockwise about the box centre.
     *
     * @return the boundary points, or an empty list when the shape has no preset silhouette
     *     (POLYGON/FREEFORM) or the box is degenerate (non-positive width/height).
     */
    public static List<PointItem> forShape(SectionShape shape, double x, double y,
                                           double width, double height, double rotationDegrees) {
        if (shape == null || width <= 0 || height <= 0) {
            return List.of();
        }
        double cx = x + width / 2.0;
        double cy = y + height / 2.0;
        List<PointItem> raw = switch (shape) {
            case RECTANGLE, SQUARE -> List.of(
                    new PointItem(x, y),
                    new PointItem(x + width, y),
                    new PointItem(x + width, y + height),
                    new PointItem(x, y + height));
            case TRIANGLE -> List.of(
                    new PointItem(cx, y),
                    new PointItem(x + width, y + height),
                    new PointItem(x, y + height));
            case PENTAGON -> regularPolygon(cx, cy, width / 2.0, height / 2.0, 5);
            case HEXAGON -> regularPolygon(cx, cy, width / 2.0, height / 2.0, 6);
            case CIRCLE, ELLIPSE -> ellipse(cx, cy, width / 2.0, height / 2.0);
            case CURVE -> curveBand(cx, y, width, height);
            case POLYGON, FREEFORM -> List.of();
        };
        return rotate(raw, cx, cy, rotationDegrees);
    }

    /**
     * A regular n-gon inscribed in the box, first vertex pointing straight up so a pentagon or
     * hexagon reads the way people draw one. Shared by every straight-sided preset, so adding an
     * octagon later is one line rather than a new algorithm.
     */
    public static List<PointItem> regularPolygon(double cx, double cy, double rx, double ry, int sides) {
        if (sides < 3) {
            return List.of();
        }
        List<PointItem> pts = new ArrayList<>(sides);
        for (int i = 0; i < sides; i++) {
            double angle = -Math.PI / 2 + 2.0 * Math.PI * i / sides;
            pts.add(new PointItem(cx + rx * Math.cos(angle), cy + ry * Math.sin(angle)));
        }
        return pts;
    }

    /** An ellipse outline (a circle when the two radii are equal) traced counter-clockwise. */
    private static List<PointItem> ellipse(double cx, double cy, double rx, double ry) {
        List<PointItem> pts = new ArrayList<>(CIRCLE_SEGMENTS);
        for (int i = 0; i < CIRCLE_SEGMENTS; i++) {
            double angle = 2.0 * Math.PI * i / CIRCLE_SEGMENTS;
            pts.add(new PointItem(cx + rx * Math.cos(angle), cy + ry * Math.sin(angle)));
        }
        return pts;
    }

    /**
     * A half-ring ("crescent") that fills the box: an outer semicircle across the top swept left to
     * right, then an inner semicircle swept back right to left. The arcs share a centre at the
     * box's bottom-centre so the band opens upward, like a balcony wrapping a stage. The inner
     * radius is half the outer, giving a band of even thickness.
     */
    private static List<PointItem> curveBand(double cx, double baseY, double width, double height) {
        double outer = width / 2.0;
        double inner = outer / 2.0;
        double yScale = height / outer; // squash the semicircle to the box height
        List<PointItem> pts = new ArrayList<>(2 * (CURVE_SEGMENTS + 1));
        // Outer arc: left (PI) sweeping over the top to right (0).
        for (int i = 0; i <= CURVE_SEGMENTS; i++) {
            double angle = Math.PI - Math.PI * i / CURVE_SEGMENTS;
            pts.add(new PointItem(cx + outer * Math.cos(angle), baseY + height - outer * Math.sin(angle) * yScale));
        }
        // Inner arc: right (0) sweeping back over the top to left (PI).
        for (int i = 0; i <= CURVE_SEGMENTS; i++) {
            double angle = Math.PI * i / CURVE_SEGMENTS;
            pts.add(new PointItem(cx + inner * Math.cos(angle), baseY + height - inner * Math.sin(angle) * yScale));
        }
        return pts;
    }

    /** Rotates every point {@code degrees} clockwise about ({@code cx},{@code cy}). */
    private static List<PointItem> rotate(List<PointItem> pts, double cx, double cy, double degrees) {
        if (pts.isEmpty() || degrees % 360.0 == 0.0) {
            return pts;
        }
        double rad = Math.toRadians(degrees);
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);
        List<PointItem> out = new ArrayList<>(pts.size());
        for (PointItem p : pts) {
            double dx = p.x() - cx;
            double dy = p.y() - cy;
            out.add(new PointItem(cx + dx * cos - dy * sin, cy + dx * sin + dy * cos));
        }
        return out;
    }
}
