package com.eventticketing.reservation.domain;

import com.eventticketing.catalog.domain.Event;
import com.eventticketing.catalog.domain.Seat;
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
 * A single seat held or booked within a {@link Booking}.
 *
 * <p>Double-booking is prevented by a database-level generated column {@code active_lock}
 * (defined in Flyway) that equals {@code event_id + '-' + seat_id} while the row is HELD or
 * BOOKED and is NULL otherwise. A unique index over that column means the database itself
 * rejects a second active hold on the same seat for the same event, even under concurrency.
 * MySQL permits many NULLs in a unique index, so released/expired rows never collide.
 * The column is intentionally NOT mapped here — it is owned entirely by the database.
 */
@Entity
@Table(name = "booking_seat")
@Getter
@Setter
@NoArgsConstructor
public class BookingSeat extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "booking_id", nullable = false)
    private Booking booking;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "seat_id", nullable = false)
    private Seat seat;

    /** Snapshot of the seat's section at hold time (id + name), insulated from later edits. */
    @Column(name = "section_id")
    private Long sectionId;

    @Column(name = "section_name", length = 120)
    private String sectionName;

    /** Currency snapshot (from the section). */
    @Column(name = "currency", length = 8)
    private String currency;

    /** Price snapshot at hold time, insulating the booking from later price changes. */
    @Column(name = "price", nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private BookingSeatStatus status;
}
