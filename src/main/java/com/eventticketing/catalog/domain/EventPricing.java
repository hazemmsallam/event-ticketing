package com.eventticketing.catalog.domain;

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
 * Per-event price override for a hall section.
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

    /** The section this event-specific price overrides. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "section_id", nullable = false)
    private Section section;

    @Column(name = "price", nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    public EventPricing(Section section, BigDecimal price) {
        this.section = section;
        this.price = price;
    }
}
