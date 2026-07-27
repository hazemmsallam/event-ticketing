package com.eventticketing.catalog.domain;

import com.eventticketing.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A non-bookable object placed in a hall's 3D layout — a table today, and any other decoration
 * introduced later (see {@link LayoutObjectType}). Deliberately separate from {@link Seat}: a
 * layout object has geometry but no seat identity, no type/price and no booking status, so it can
 * never enter availability or booking calculations.
 *
 * <p>Position and footprint share the seat coordinate space so the same 2D/3D editor can arrange
 * both: {@code layoutX}/{@code layoutY} is the top-down position and {@code layoutWidth}/
 * {@code layoutDepth} the footprint. {@code layoutZ} (vertical offset off the floor) and
 * {@code objectHeight} (physical height) add the extra dimensions a flat seat does not need.
 */
@Entity
@Table(name = "layout_object")
@Getter
@Setter
@NoArgsConstructor
public class LayoutObject extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hall_id", nullable = false)
    private Hall hall;

    @Enumerated(EnumType.STRING)
    @Column(name = "object_type", nullable = false, length = 24)
    private LayoutObjectType objectType;

    /** Footprint shape; required for {@link LayoutObjectType#TABLE}, may be null for future types. */
    @Enumerated(EnumType.STRING)
    @Column(name = "shape", length = 16)
    private TableShape shape;

    /** Optional free-text name/label shown in the editor and customer view. */
    @Column(name = "label", length = 80)
    private String label;

    /** Top-down position, in the same pixel space as {@link Seat#getLayoutX()}. */
    @Column(name = "layout_x")
    private Integer layoutX;

    @Column(name = "layout_y")
    private Integer layoutY;

    /** Vertical position (elevation off the floor); usually 0 for a table standing on the ground. */
    @Column(name = "layout_z")
    private Integer layoutZ;

    @Column(name = "rotation_degrees")
    private Integer rotationDegrees;

    /** Footprint width (X). For a circle this is the diameter. */
    @Column(name = "layout_width")
    private Integer layoutWidth;

    /** Footprint depth/length (Y). Equals the width for square and circular tables. */
    @Column(name = "layout_depth")
    private Integer layoutDepth;

    /** Physical (vertical) height of the object. */
    @Column(name = "object_height")
    private Integer objectHeight;
}
