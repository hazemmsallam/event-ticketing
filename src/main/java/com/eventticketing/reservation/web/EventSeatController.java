package com.eventticketing.reservation.web;

import com.eventticketing.reservation.dto.EventAvailabilityResponse;
import com.eventticketing.reservation.dto.EventSeatMapResponse;
import com.eventticketing.reservation.service.ReservationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Live availability endpoints the mobile client polls: a per-seat map for seated events and a
 * capacity summary for general-admission events.
 */
@RestController
@RequestMapping("/api/events/{eventId}")
public class EventSeatController {

    private final ReservationService reservationService;

    public EventSeatController(ReservationService reservationService) {
        this.reservationService = reservationService;
    }

    @GetMapping("/seats")
    public EventSeatMapResponse seatMap(@PathVariable Long eventId) {
        return reservationService.getSeatMap(eventId);
    }

    @GetMapping("/availability")
    public EventAvailabilityResponse availability(@PathVariable Long eventId) {
        return reservationService.getAvailability(eventId);
    }
}
