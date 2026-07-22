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
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "seat", uniqueConstraints = {
        @UniqueConstraint(name = "uq_seat_hall_label", columnNames = {"hall_id", "label"})
})
@Getter
@Setter
@NoArgsConstructor
public class Seat extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hall_id", nullable = false)
    private Hall hall;

    /** Alphabetical row prefix, e.g. "A", "B", ... "AA". */
    @Column(name = "row_label", nullable = false, length = 8)
    private String rowLabel;

    /** 1-based row position, used for ordering. */
    @Column(name = "row_index", nullable = false)
    private int rowIndex;

    /** 1-based seat number within the row. */
    @Column(name = "seat_number", nullable = false)
    private int seatNumber;

    /** Full seat label, e.g. "A1". Unique within a hall. */
    @Column(name = "label", nullable = false, length = 16)
    private String label;

    @Enumerated(EnumType.STRING)
    @Column(name = "seat_type", nullable = false, length = 16)
    private SeatType seatType;

    @Column(name = "layout_x")
    private Integer layoutX;

    @Column(name = "layout_y")
    private Integer layoutY;

    @Column(name = "rotation_degrees")
    private Integer rotationDegrees;

    @Column(name = "layout_width")
    private Integer layoutWidth;

    @Column(name = "layout_height")
    private Integer layoutHeight;

    @Column(name = "section_name", length = 80)
    private String sectionName;
}
