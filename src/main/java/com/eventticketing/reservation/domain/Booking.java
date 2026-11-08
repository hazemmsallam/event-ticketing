package com.eventticketing.reservation.domain;

import com.eventticketing.catalog.domain.Event;
import com.eventticketing.common.domain.BaseEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * One order: any mix of selected seats ({@link BookingSeat}) and general-admission tickets in one
 * or more sections ({@link BookingGaLine}), paid for together.
 */
@Entity
@Table(name = "booking")
@Getter
@Setter
@NoArgsConstructor
public class Booking extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    /** Identifies the buyer. Placeholder until authentication is added. */
    @Column(name = "customer_ref", nullable = false)
    private String customerRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private BookingStatus status;

    /** Combined number of held seats and general-admission tickets. */
    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "total_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "payment_ref", length = 64)
    private String paymentRef;

    @OneToMany(mappedBy = "booking", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<BookingSeat> bookingSeats = new ArrayList<>();

    @OneToMany(mappedBy = "booking", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<BookingGaLine> gaLines = new ArrayList<>();

    /** Tickets generated once payment confirms the booking (one per seat and per GA ticket). */
    @OneToMany(mappedBy = "booking", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Ticket> tickets = new ArrayList<>();

    public void addBookingSeat(BookingSeat seat) {
        seat.setBooking(this);
        this.bookingSeats.add(seat);
    }

    public void addGaLine(BookingGaLine line) {
        line.setBooking(this);
        this.gaLines.add(line);
    }

    public void addTicket(Ticket ticket) {
        ticket.setBooking(this);
        this.tickets.add(ticket);
    }

    public List<BookingSeat> heldSeats() {
        return bookingSeats.stream().filter(bs -> bs.getStatus() == BookingSeatStatus.HELD).toList();
    }

    public int generalAdmissionQuantity() {
        return gaLines.stream().mapToInt(BookingGaLine::getQuantity).sum();
    }

    public boolean isExpired(Instant now) {
        return expiresAt != null && !expiresAt.isAfter(now);
    }
}
