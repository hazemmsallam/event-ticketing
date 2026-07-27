package com.eventticketing.catalog.dto;

import com.eventticketing.catalog.domain.LayoutObject;
import com.eventticketing.catalog.domain.LayoutObjectType;
import com.eventticketing.catalog.domain.TableShape;

/**
 * A non-bookable layout object as returned to admin and customer clients. Carries geometry only —
 * no availability, price or booking status, because these objects are never bookable.
 */
public record LayoutObjectResponse(
        Long id,
        LayoutObjectType objectType,
        TableShape shape,
        String label,
        Integer layoutX,
        Integer layoutY,
        Integer layoutZ,
        Integer rotationDegrees,
        Integer layoutWidth,
        Integer layoutDepth,
        Integer objectHeight
) {
    public static LayoutObjectResponse from(LayoutObject o) {
        return new LayoutObjectResponse(o.getId(), o.getObjectType(), o.getShape(), o.getLabel(),
                o.getLayoutX(), o.getLayoutY(), o.getLayoutZ(), o.getRotationDegrees(),
                o.getLayoutWidth(), o.getLayoutDepth(), o.getObjectHeight());
    }
}
