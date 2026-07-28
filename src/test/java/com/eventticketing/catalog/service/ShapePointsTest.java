package com.eventticketing.catalog.service;

import com.eventticketing.catalog.domain.SectionShape;
import com.eventticketing.catalog.dto.PointItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ShapePoints}: every preset must produce a polygon whose interior matches the
 * box it was inscribed in, and non-preset shapes must produce nothing.
 */
class ShapePointsTest {

    // A 200x100 box with its top-left at (100, 50); centre is (200, 100).
    private static final double X = 100, Y = 50, W = 200, H = 100;
    private static final double CX = X + W / 2, CY = Y + H / 2;

    @Test
    void rectangleHasFourCornersAndContainsCentre() {
        List<PointItem> pts = ShapePoints.forShape(SectionShape.RECTANGLE, X, Y, W, H, 0);
        assertEquals(4, pts.size());
        assertTrue(SectionGeometry.contains(pts, CX, CY));
        assertFalse(SectionGeometry.contains(pts, X - 10, CY));
    }

    @Test
    void triangleHasThreeVerticesWithApexAtTopCentre() {
        List<PointItem> pts = ShapePoints.forShape(SectionShape.TRIANGLE, X, Y, W, H, 0);
        assertEquals(3, pts.size());
        assertEquals(CX, pts.get(0).x(), 1e-9);
        assertEquals(Y, pts.get(0).y(), 1e-9);
        // Apex region (just below the top-centre vertex) is inside; the top corners are outside.
        assertTrue(SectionGeometry.contains(pts, CX, Y + H - 5));
        assertFalse(SectionGeometry.contains(pts, X + 5, Y + 5));
    }

    @Test
    void circleContainsCentreButNotBoxCorners() {
        List<PointItem> pts = ShapePoints.forShape(SectionShape.CIRCLE, X, Y, W, H, 0);
        assertTrue(pts.size() > 8);
        assertTrue(SectionGeometry.contains(pts, CX, CY));
        // A box corner lies outside an inscribed ellipse.
        assertFalse(SectionGeometry.contains(pts, X + 1, Y + 1));
    }

    @Test
    void curveProducesABandOpeningUpward() {
        List<PointItem> pts = ShapePoints.forShape(SectionShape.CURVE, X, Y, W, H, 0);
        assertFalse(pts.isEmpty());
        // The solid band fills the lower-left horn of the crescent...
        assertTrue(SectionGeometry.contains(pts, X + 25, Y + H - 5));
        // ...while the open middle below the inner arc is the hole.
        assertFalse(SectionGeometry.contains(pts, CX, Y + H - 5));
    }

    @Test
    void freeformAndPolygonGenerateNothing() {
        assertTrue(ShapePoints.forShape(SectionShape.POLYGON, X, Y, W, H, 0).isEmpty());
        assertTrue(ShapePoints.forShape(SectionShape.FREEFORM, X, Y, W, H, 0).isEmpty());
    }

    @Test
    void degenerateBoxGeneratesNothing() {
        assertTrue(ShapePoints.forShape(SectionShape.CIRCLE, X, Y, 0, H, 0).isEmpty());
        assertTrue(ShapePoints.forShape(null, X, Y, W, H, 0).isEmpty());
    }

    @Test
    void rotationKeepsTheCentreInsideAndMovesVertices() {
        List<PointItem> upright = ShapePoints.forShape(SectionShape.RECTANGLE, X, Y, W, H, 0);
        List<PointItem> turned = ShapePoints.forShape(SectionShape.RECTANGLE, X, Y, W, H, 90);
        // Rotation is about the centre, so the centre stays inside.
        assertTrue(SectionGeometry.contains(turned, CX, CY));
        // A 90° turn actually moves the corners.
        assertFalse(Math.abs(upright.get(0).x() - turned.get(0).x()) < 1e-6
                && Math.abs(upright.get(0).y() - turned.get(0).y()) < 1e-6);
    }
}
