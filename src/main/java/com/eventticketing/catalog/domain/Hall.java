package com.eventticketing.catalog.domain;

import com.eventticketing.common.domain.BaseEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "hall")
@Getter
@Setter
@NoArgsConstructor
public class Hall extends BaseEntity {

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "address")
    private String address;

    /** Total number of seats (seated halls) or standing capacity (non-seated halls). */
    @Column(name = "capacity", nullable = false)
    private int capacity;

    @Column(name = "is_seated", nullable = false)
    private boolean seated;

    /** Layout dimensions; null for non-seated halls. */
    @Column(name = "num_rows")
    private Integer numRows;

    @Column(name = "num_columns")
    private Integer numColumns;

    @Enumerated(EnumType.STRING)
    @Column(name = "numbering_scheme", length = 40)
    private SeatNumberingScheme numberingScheme;

    @Column(name = "layout_width")
    private Integer layoutWidth;

    @Column(name = "layout_height")
    private Integer layoutHeight;

    @OneToMany(mappedBy = "hall", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("rowIndex ASC, seatNumber ASC")
    private List<Seat> seats = new ArrayList<>();

    /**
     * Non-bookable decoration objects (tables, …) placed in this hall's layout. Kept separate from
     * {@link #seats} so capacity and booking logic never sees them.
     */
    @OneToMany(mappedBy = "hall", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    private List<LayoutObject> layoutObjects = new ArrayList<>();

    /** First-class hall sections (dynamic name/price/mode/capacity/geometry). */
    @OneToMany(mappedBy = "hall", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    private List<Section> sections = new ArrayList<>();

    public void addSeat(Seat seat) {
        seat.setHall(this);
        this.seats.add(seat);
    }

    public void addLayoutObject(LayoutObject layoutObject) {
        layoutObject.setHall(this);
        this.layoutObjects.add(layoutObject);
    }

    public void addSection(Section section) {
        section.setHall(this);
        this.sections.add(section);
    }
}
