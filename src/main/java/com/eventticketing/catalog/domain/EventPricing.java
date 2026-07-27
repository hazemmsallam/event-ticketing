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

import java.math.BigDecimal;

/**
 * Price for a seat type at a specific event. For non-seated (general admission)
 * events a single row with a {@code null} seatType holds the admission price.
 */
@Entity
@Table(name = "event_pricing")
@Getter
@Setter
@NoArgsConstructor
public class EventPricing extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    /** Null means general admission (non-seated hall). Legacy — prefer {@link #section}. */
    @Enumerated(EnumType.STRING)
    @Column(name = "seat_type", length = 16)
    private SeatType seatType;

    /**
     * The section this price applies to (per-event override of the section's default price). When
     * set, this is the authoritative source for that section's seats / general-admission tickets.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "section_id")
    private Section section;

    @Column(name = "price", nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    public EventPricing(SeatType seatType, BigDecimal price) {
        this.seatType = seatType;
        this.price = price;
    }

    public EventPricing(Section section, BigDecimal price) {
        this.section = section;
        this.price = price;
    }
}
