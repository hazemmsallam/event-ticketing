package com.eventticketing.reservation.web;

import com.eventticketing.reservation.dto.BookingResponse;
import com.eventticketing.reservation.dto.CreateBookingRequest;
import com.eventticketing.reservation.dto.PaymentResponse;
import com.eventticketing.reservation.service.ReservationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    private final ReservationService reservationService;

    public BookingController(ReservationService reservationService) {
        this.reservationService = reservationService;
    }

    /** Reserve seats (seated) or tickets (general admission); returns the hold and its expiry. */
    @PostMapping
    public ResponseEntity<BookingResponse> create(@Valid @RequestBody CreateBookingRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(reservationService.createBooking(request));
    }

    @GetMapping("/{id}")
    public BookingResponse get(@PathVariable Long id) {
        return reservationService.getBooking(id);
    }

    /** Fake payment that always succeeds, confirming the hold into a booking. */
    @PostMapping("/{id}/payment")
    public PaymentResponse pay(@PathVariable Long id) {
        return reservationService.pay(id);
    }

    /** Release a pending hold before payment. */
    @DeleteMapping("/{id}")
    public BookingResponse cancel(@PathVariable Long id) {
        return reservationService.cancelBooking(id);
    }
}
