package com.eventticketing.web.mobile;

import com.eventticketing.catalog.dto.EventResponse;
import com.eventticketing.catalog.dto.EventSummaryResponse;
import com.eventticketing.catalog.service.EventService;
import com.eventticketing.reservation.dto.EventAvailabilityResponse;
import com.eventticketing.reservation.dto.EventSeatMapResponse;
import com.eventticketing.reservation.service.ReservationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Mobile: browse bookable events and poll live availability. The seat map and availability
 * endpoints are what the app polls for live data.
 */
@RestController
@RequestMapping("/api/events")
public class EventBrowseController {

    private final EventService eventService;
    private final ReservationService reservationService;

    public EventBrowseController(EventService eventService, ReservationService reservationService) {
        this.eventService = eventService;
        this.reservationService = reservationService;
    }

    /** Events open to booking (PUBLISHED / SOLD_OUT). */
    @GetMapping("/available")
    public List<EventSummaryResponse> listAvailable() {
        return eventService.listAvailable();
    }

    @GetMapping("/{id}")
    public EventResponse get(@PathVariable Long id) {
        return eventService.get(id);
    }

    /** Live seat map for a seated event (poll this). */
    @GetMapping("/{id}/seats")
    public EventSeatMapResponse seatMap(@PathVariable Long id) {
        return reservationService.getSeatMap(id);
    }

    /** Live availability for a non-seated event (poll this). */
    @GetMapping("/{id}/availability")
    public EventAvailabilityResponse availability(@PathVariable Long id) {
        return reservationService.getAvailability(id);
    }
}
