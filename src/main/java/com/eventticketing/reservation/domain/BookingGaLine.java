package com.eventticketing.reservation.domain;

import com.eventticketing.catalog.domain.Event;
import com.eventticketing.catalog.domain.Section;
import com.eventticketing.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * General-admission tickets in one section within a {@link Booking}. A booking has at most one
 * line per section.
 *
 * <p>GA tickets have no individual identity, so no unique index can prevent overselling. Section
 * capacity is instead enforced by counting lines of live bookings while the event row is locked.
 */
@Entity
@Table(name = "booking_ga_line")
@Getter
@Setter
@NoArgsConstructor
public class BookingGaLine extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "booking_id", nullable = false)
    private Booking booking;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    /** Null only for legacy orders whose section could not be resolved. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "section_id")
    private Section section;

    /** Snapshot of the section name at hold time, insulated from later edits. */
    @Column(name = "section_name", length = 120)
    private String sectionName;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    /** Price snapshot at hold time, insulating the booking from later price changes. */
    @Column(name = "unit_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal unitPrice;

    @Column(name = "service_fee_per_ticket", precision = 12, scale = 2, nullable = false)
    private BigDecimal serviceFeePerTicket = BigDecimal.ZERO;

    public BigDecimal lineTotal() {
        return unitPrice.multiply(BigDecimal.valueOf(quantity));
    }
}
