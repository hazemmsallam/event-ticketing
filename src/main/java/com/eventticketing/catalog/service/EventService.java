package com.eventticketing.catalog.service;

import com.eventticketing.catalog.domain.Event;
import com.eventticketing.catalog.domain.EventPricing;
import com.eventticketing.catalog.domain.EventStatus;
import com.eventticketing.catalog.domain.Hall;
import com.eventticketing.catalog.domain.Organizer;
import com.eventticketing.catalog.domain.Section;
import com.eventticketing.catalog.dto.CreateEventRequest;
import com.eventticketing.catalog.dto.EventResponse;
import com.eventticketing.catalog.dto.EventSummaryResponse;
import com.eventticketing.catalog.dto.PricingItem;
import com.eventticketing.catalog.dto.PricingResponse;
import com.eventticketing.catalog.dto.SetEventPricingRequest;
import com.eventticketing.catalog.repository.EventRepository;
import com.eventticketing.common.exception.BusinessRuleException;
import com.eventticketing.common.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class EventService {

    private final EventRepository eventRepository;
    private final OrganizerService organizerService;
    private final HallService hallService;

    public EventService(EventRepository eventRepository,
                        OrganizerService organizerService,
                        HallService hallService) {
        this.eventRepository = eventRepository;
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
    public EventResponse update(Long id, CreateEventRequest request) {
        Event event = getEntity(id);
        if (!request.endAt().isAfter(request.startAt())) {
            throw new BusinessRuleException("Event endAt must be after startAt.");
        }
        Organizer organizer = organizerService.getEntity(request.organizerId());
        Hall hall = hallService.getEntity(request.hallId());

        boolean changingHall = !hall.getId().equals(event.getHall().getId());
        if (changingHall && event.getStatus() != EventStatus.DRAFT) {
            throw new BusinessRuleException("The hall can only be changed while the event is in DRAFT.");
        }
        if (request.maxCapacity() > hall.getCapacity()) {
            throw new BusinessRuleException(
                    "maxCapacity %d exceeds hall capacity %d.".formatted(request.maxCapacity(), hall.getCapacity()));
        }

        event.setName(request.name());
        event.setDescription(request.description());
        event.setCategory(request.category());
        event.setStartAt(request.startAt());
        event.setEndAt(request.endAt());
        event.setOrganizer(organizer);
        event.setMaxCapacity(request.maxCapacity());
        if (changingHall) {
            // Sections differ in the new hall, so previously configured pricing no longer applies.
            event.setHall(hall);
            event.clearPricing();
        }
        return toResponse(event);
    }

    @Transactional
    public void delete(Long id) {
        eventRepository.delete(getEntity(id));
    }

    @Transactional
    public EventResponse setPricing(Long eventId, SetEventPricingRequest request) {
        Event event = getEntity(eventId);
        Hall hall = event.getHall();
        event.clearPricing();

        if (hall.getSections().isEmpty()) {
            throw new BusinessRuleException(
                    "Hall '" + hall.getName() + "' has no sections. Add a section before setting prices.");
        }

        Map<Long, Section> byId = hall.getSections().stream()
                .collect(Collectors.toMap(Section::getId, Function.identity()));
        Set<Long> seen = new HashSet<>();
        for (PricingItem item : request.prices()) {
            Section section = byId.get(item.sectionId());
            if (section == null) {
                throw new BusinessRuleException(
                        "Section %d does not belong to hall '%s'.".formatted(item.sectionId(), hall.getName()));
            }
            if (!seen.add(item.sectionId())) {
                throw new BusinessRuleException("Duplicate price for section '" + section.getName() + "'.");
            }
            event.addPricing(new EventPricing(section, item.price()));
        }
        return toResponse(event);
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

        if (hall.getSections().isEmpty()) {
            throw new BusinessRuleException("Cannot publish: the hall has no sections.");
        }
        if (hall.isSeated() && hall.getSeats().stream().anyMatch(seat -> seat.getSection() == null)) {
            throw new BusinessRuleException(
                    "Cannot publish: every seat must belong to a seated section.");
        }

        Set<Long> pricedSections = event.getPricing().stream()
                .map(p -> p.getSection().getId())
                .collect(Collectors.toSet());
        for (Section section : hall.getSections()) {
            // Prices live only on the event: there is no hall-level fallback to inherit.
            if (!pricedSections.contains(section.getId())) {
                throw new BusinessRuleException(
                        "Cannot publish: missing price for section '" + section.getName() + "'.");
            }
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
