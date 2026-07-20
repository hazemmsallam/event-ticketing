package com.eventticketing.catalog.service;

import com.eventticketing.catalog.domain.Event;
import com.eventticketing.catalog.domain.EventPricing;
import com.eventticketing.catalog.domain.EventStatus;
import com.eventticketing.catalog.domain.Hall;
import com.eventticketing.catalog.domain.Organizer;
import com.eventticketing.catalog.domain.SeatType;
import com.eventticketing.catalog.dto.CreateEventRequest;
import com.eventticketing.catalog.dto.EventResponse;
import com.eventticketing.catalog.dto.EventSummaryResponse;
import com.eventticketing.catalog.dto.PricingItem;
import com.eventticketing.catalog.dto.PricingResponse;
import com.eventticketing.catalog.dto.SetEventPricingRequest;
import com.eventticketing.catalog.repository.EventRepository;
import com.eventticketing.catalog.repository.SeatRepository;
import com.eventticketing.common.exception.BusinessRuleException;
import com.eventticketing.common.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class EventService {

    private final EventRepository eventRepository;
    private final SeatRepository seatRepository;
    private final OrganizerService organizerService;
    private final HallService hallService;

    public EventService(EventRepository eventRepository,
                        SeatRepository seatRepository,
                        OrganizerService organizerService,
                        HallService hallService) {
        this.eventRepository = eventRepository;
        this.seatRepository = seatRepository;
        this.organizerService = organizerService;
        this.hallService = hallService;
    }

    @Transactional
    public EventResponse create(CreateEventRequest request) {
        if (!request.endAt().isAfter(request.startAt())) {
            throw new BusinessRuleException("Event endAt must be after startAt.");
        }
        Organizer organizer = organizerService.getEntity(request.organizerId());
        Hall hall = hallService.getEntity(request.hallId());

        if (request.maxCapacity() > hall.getCapacity()) {
            throw new BusinessRuleException(
                    "maxCapacity %d exceeds hall capacity %d.".formatted(request.maxCapacity(), hall.getCapacity()));
        }

        Event event = new Event();
        event.setName(request.name());
        event.setDescription(request.description());
        event.setCategory(request.category());
        event.setStartAt(request.startAt());
        event.setEndAt(request.endAt());
        event.setOrganizer(organizer);
        event.setHall(hall);
        event.setMaxCapacity(request.maxCapacity());
        event.setStatus(EventStatus.DRAFT);

        return toResponse(eventRepository.save(event));
    }

    @Transactional
    public EventResponse setPricing(Long eventId, SetEventPricingRequest request) {
        Event event = getEntity(eventId);
        Hall hall = event.getHall();

        if (hall.isSeated()) {
            validateSeatedPricing(hall, request.prices());
        } else {
            validateGeneralAdmissionPricing(request.prices());
        }

        event.clearPricing();
        for (PricingItem item : request.prices()) {
            event.addPricing(new EventPricing(item.seatType(), item.price()));
        }
        return toResponse(event);
    }

    private void validateSeatedPricing(Hall hall, List<PricingItem> items) {
        Set<SeatType> seen = new HashSet<>();
        Set<SeatType> present = new HashSet<>(seatRepository.findDistinctSeatTypesByHallId(hall.getId()));
        for (PricingItem item : items) {
            if (item.seatType() == null) {
                throw new BusinessRuleException("Seated events require a seatType on every price line.");
            }
            if (!present.contains(item.seatType())) {
                throw new BusinessRuleException(
                        "Seat type %s does not exist in hall '%s'.".formatted(item.seatType(), hall.getName()));
            }
            if (!seen.add(item.seatType())) {
                throw new BusinessRuleException("Duplicate price for seat type " + item.seatType() + ".");
            }
        }
    }

    private void validateGeneralAdmissionPricing(List<PricingItem> items) {
        if (items.size() != 1 || items.get(0).seatType() != null) {
            throw new BusinessRuleException(
                    "Non-seated events require exactly one price line with a null seatType (general admission).");
        }
    }

    @Transactional
    public EventResponse updateStatus(Long eventId, EventStatus target) {
        Event event = getEntity(eventId);
        EventStatus current = event.getStatus();

        if (current == target) {
            return toResponse(event);
        }
        if (current == EventStatus.CANCELLED) {
            throw new BusinessRuleException("A cancelled event cannot change status.");
        }
        if (target == EventStatus.PUBLISHED) {
            requirePricingComplete(event);
        }
        event.setStatus(target);
        return toResponse(event);
    }

    private void requirePricingComplete(Event event) {
        Hall hall = event.getHall();
        Set<SeatType> priced = new HashSet<>();
        boolean hasGeneralAdmission = false;
        for (EventPricing p : event.getPricing()) {
            if (p.getSeatType() == null) {
                hasGeneralAdmission = true;
            } else {
                priced.add(p.getSeatType());
            }
        }

        if (hall.isSeated()) {
            List<SeatType> present = seatRepository.findDistinctSeatTypesByHallId(hall.getId());
            for (SeatType type : present) {
                if (!priced.contains(type)) {
                    throw new BusinessRuleException(
                            "Cannot publish: missing price for seat type " + type + ".");
                }
            }
        } else if (!hasGeneralAdmission) {
            throw new BusinessRuleException("Cannot publish: missing general-admission price.");
        }
    }

    @Transactional(readOnly = true)
    public List<EventSummaryResponse> listAvailable() {
        return eventRepository.findByStatusInOrderByStartAtAsc(
                        List.of(EventStatus.PUBLISHED, EventStatus.SOLD_OUT)).stream()
                .map(EventSummaryResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<EventSummaryResponse> listAll() {
        return eventRepository.findAll().stream().map(EventSummaryResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public EventResponse get(Long id) {
        return toResponse(getEntity(id));
    }

    @Transactional(readOnly = true)
    public Event getEntity(Long id) {
        return eventRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("Event", id));
    }

    private EventResponse toResponse(Event event) {
        List<PricingResponse> pricing = event.getPricing().stream()
                .map(PricingResponse::from)
                .toList();
        return EventResponse.from(event, pricing);
    }
}
