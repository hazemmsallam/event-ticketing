package com.eventticketing.catalog.web;

import com.eventticketing.catalog.dto.CreateEventRequest;
import com.eventticketing.catalog.dto.EventResponse;
import com.eventticketing.catalog.dto.EventSummaryResponse;
import com.eventticketing.catalog.dto.SetEventPricingRequest;
import com.eventticketing.catalog.dto.UpdateEventStatusRequest;
import com.eventticketing.catalog.service.EventService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/events")
public class EventController {

    private final EventService eventService;

    public EventController(EventService eventService) {
        this.eventService = eventService;
    }

    @PostMapping
    public ResponseEntity<EventResponse> create(@Valid @RequestBody CreateEventRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(eventService.create(request));
    }

    /** Admin listing: every event regardless of status. */
    @GetMapping
    public List<EventSummaryResponse> listAll() {
        return eventService.listAll();
    }

    /** Mobile listing: events open to booking (PUBLISHED / SOLD_OUT). */
    @GetMapping("/available")
    public List<EventSummaryResponse> listAvailable() {
        return eventService.listAvailable();
    }

    @GetMapping("/{id}")
    public EventResponse get(@PathVariable Long id) {
        return eventService.get(id);
    }

    @PutMapping("/{id}")
    public EventResponse update(@PathVariable Long id, @Valid @RequestBody CreateEventRequest request) {
        return eventService.update(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        eventService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/pricing")
    public EventResponse setPricing(@PathVariable Long id, @Valid @RequestBody SetEventPricingRequest request) {
        return eventService.setPricing(id, request);
    }

    @PatchMapping("/{id}/status")
    public EventResponse updateStatus(@PathVariable Long id, @Valid @RequestBody UpdateEventStatusRequest request) {
        return eventService.updateStatus(id, request.status());
    }
}
