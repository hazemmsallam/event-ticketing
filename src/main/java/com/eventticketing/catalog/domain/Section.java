package com.eventticketing.catalog.domain;

import com.eventticketing.catalog.dto.PointItem;
import com.eventticketing.common.domain.BaseEntity;
import jakarta.persistence.Column;
import java.math.BigDecimal;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.ArrayList;
import java.util.List;

/**
 * A first-class area of a hall with its own name, booking mode, capacity and boundary geometry —
 * not merely a label placed over seats.
 *
 * <p>A section deliberately carries <em>no</em> price: the hall describes the venue, and the same
 * area is worth different amounts at different events. Prices live only on
 * {@link com.eventticketing.catalog.domain.EventPricing}, one line per (event, section).
 *
 * <p>Names are fully dynamic (any text): the business can create "Royal", "Balcony Left",
 * "Family Zone", etc. without any code change. A hall may mix
 * {@link SectionBookingMode#SEATED} sections
 * (containing selectable {@link Seat}s) and {@link SectionBookingMode#GENERAL_ADMISSION} sections
 * (open areas with an admin-set capacity) at the same time.
 *
 * <p>Boundary geometry is stored as the raw polygon {@link #points} (a list of {@code {x,y}}
 * in the hall's layout-pixel space, the same coordinate system seats and tables use) so arbitrary,
 * irregular, free-form shapes are representable without a shape-specific schema.
 */
@Entity
@Table(name = "section")
@Getter
@Setter
@NoArgsConstructor
public class Section extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hall_id", nullable = false)
    private Hall hall;

    /** Free-text section name, e.g. "Royal", "Balcony Left". Dynamic — never a fixed enum. */
    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "booking_mode", nullable = false, length = 24)
    private SectionBookingMode bookingMode;

    /**
     * Admin-configured capacity. Authoritative for {@link SectionBookingMode#GENERAL_ADMISSION};
     * for {@link SectionBookingMode#SEATED} it is derived from the bookable seats and may be null.
     */
    @Column(name = "capacity")
    private Integer capacity;

    @Column(name = "service_fee_per_ticket", precision = 12, scale = 2)
    private BigDecimal serviceFeePerTicket;

    @Enumerated(EnumType.STRING)
    @Column(name = "shape_kind", length = 16)
    private SectionShape shapeKind;

    /**
     * Boundary polygon in layout pixels, stored in a MySQL {@code JSON} column as
     * {@code [{"x":..,"y":..}, ...]}. Hibernate converts it both ways, so the database rejects
     * malformed JSON and a corrupt value fails loudly on load instead of reading as "no boundary".
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "points", nullable = false, columnDefinition = "json")
    private List<PointItem> points = new ArrayList<>();

    /** Optional display colour (hex, e.g. "#2E75DC") so 2D/3D views can distinguish sections. */
    @Column(name = "color", length = 16)
    private String color;
}
