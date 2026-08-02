package com.eventticketing.web.mobile;

import com.eventticketing.common.security.CurrentCustomer;
import com.eventticketing.common.security.CustomerId;
import com.eventticketing.common.security.UnpaidHoldThrottle;
import com.eventticketing.reservation.dto.BookingResponse;
import com.eventticketing.reservation.dto.ChangeSeatsRequest;
import com.eventticketing.reservation.dto.CreateBookingRequest;
import com.eventticketing.reservation.dto.PaymentResponse;
import com.eventticketing.reservation.service.PaymentService;
import com.eventticketing.reservation.service.ReservationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Mobile: hold seats/tickets, view history, change seats, pay, and cancel.
 *
 * <p>Every endpoint identifies the caller from the {@code Authorization} header via
 * {@link CurrentCustomer}. Nothing here trusts an identity from the request body or a custom
 * header, because both are client-controlled and would let an abuser rotate identity to escape
 * the per-user hold quota and rate limit.
 */
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    private final ReservationService reservationService;
    private final PaymentService paymentService;
    private final UnpaidHoldThrottle unpaidHoldThrottle;

    public BookingController(ReservationService reservationService,
                             PaymentService paymentService,
                             UnpaidHoldThrottle unpaidHoldThrottle) {
        this.reservationService = reservationService;
        this.paymentService = paymentService;
        this.unpaidHoldThrottle = unpaidHoldThrottle;
    }

    /**
     * Reserve seats (seated) or tickets (general admission); returns the hold and its expiry.
     *
     * <p>Guarded by three independent limits: the payload cap on the request DTO (400), the
     * unpaid-hold throttle (429 with {@code Retry-After}) which only bites customers who open
     * holds and never pay, and the single-active-hold quota enforced in the service (409).
     */
    @PostMapping
    public ResponseEntity<BookingResponse> create(@Valid @RequestBody CreateBookingRequest request,
                                                  @CurrentCustomer CustomerId customer) {
        unpaidHoldThrottle.recordHoldAttempt(customer);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(reservationService.createBooking(request, customer));
    }

    /** Booking history for the current customer, newest first. */
    @GetMapping
    public List<BookingResponse> list(@CurrentCustomer CustomerId customer) {
        return reservationService.listBookings(customer.ref());
    }

    @GetMapping("/{id}")
    public BookingResponse get(@PathVariable Long id, @CurrentCustomer CustomerId customer) {
        return reservationService.getBooking(id, customer.ref());
    }

    /** Change the seats on a still-unpaid hold (atomic swap; resets the hold timer). */
    @PutMapping("/{id}/seats")
    public BookingResponse changeSeats(@PathVariable Long id,
                                       @CurrentCustomer CustomerId customer,
                                       @Valid @RequestBody ChangeSeatsRequest request) {
        return reservationService.changeSeats(id, customer.ref(), request.seatIds());
    }

    /** Pay for a hold: charges the gateway (fake) outside the DB transaction and confirms it. */
    @PostMapping("/{id}/payment")
    public PaymentResponse pay(@PathVariable Long id, @CurrentCustomer CustomerId customer) {
        return paymentService.pay(id, customer.ref());
    }

    /** Release a pending hold before payment. */
    @DeleteMapping("/{id}")
    public BookingResponse cancel(@PathVariable Long id, @CurrentCustomer CustomerId customer) {
        return reservationService.cancelBooking(id, customer.ref());
    }
}
