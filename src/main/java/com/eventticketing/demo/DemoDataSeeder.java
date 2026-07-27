package com.eventticketing.demo;

import com.eventticketing.catalog.domain.EventStatus;
import com.eventticketing.catalog.domain.SeatType;
import com.eventticketing.catalog.dto.CreateEventRequest;
import com.eventticketing.catalog.dto.CreateHallRequest;
import com.eventticketing.catalog.dto.CreateOrganizerRequest;
import com.eventticketing.catalog.dto.HallResponse;
import com.eventticketing.catalog.dto.PricingItem;
import com.eventticketing.catalog.dto.RowTypeRange;
import com.eventticketing.catalog.dto.SetEventPricingRequest;
import com.eventticketing.catalog.service.EventService;
import com.eventticketing.catalog.service.HallService;
import com.eventticketing.catalog.service.OrganizerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Seeds a small predefined catalog (organizer, one seated + one non-seated hall, and two
 * published events) so the API can be exercised immediately. Enable with
 * {@code app.seed-demo-data=true}. Skips seeding if any organizer already exists.
 */
@Component
@ConditionalOnProperty(name = "app.seed-demo-data", havingValue = "true")
public class DemoDataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private final OrganizerService organizerService;
    private final HallService hallService;
    private final EventService eventService;

    public DemoDataSeeder(OrganizerService organizerService, HallService hallService, EventService eventService) {
        this.organizerService = organizerService;
        this.hallService = hallService;
        this.eventService = eventService;
    }

    @Override
    public void run(String... args) {
        if (!organizerService.list().isEmpty()) {
            log.info("Demo data already present; skipping seed.");
            return;
        }
        log.info("Seeding demo data...");

        Long organizerId = organizerService.create(
                new CreateOrganizerRequest("Starlight Productions", "hello@starlight.example", "+1-555-0100")).id();

        // Seated hall: 5 rows x 8 columns. Row 1 = VIP, rows 2-3 = PREMIUM, rows 4-5 = REGULAR.
        HallResponse grandTheatre = hallService.create(new CreateHallRequest(
                "Grand Theatre", "10 Opera Ave", true, 5, 8, null,
                List.of(
                        new RowTypeRange(1, 1, SeatType.VIP),
                        new RowTypeRange(2, 3, SeatType.PREMIUM),
                        new RowTypeRange(4, 5, SeatType.REGULAR)
                ), null));

        // Non-seated hall: general admission, capacity 300.
        HallResponse openArena = hallService.create(new CreateHallRequest(
                "Open Arena", "200 Festival Rd", false, null, null, null, null, 300));

        Instant base = Instant.now().plus(7, ChronoUnit.DAYS);

        Long concertId = eventService.create(new CreateEventRequest(
                "Symphony Under the Stars", "An evening of orchestral classics.", "Music",
                base, base.plus(3, ChronoUnit.HOURS),
                organizerId, grandTheatre.id(), grandTheatre.capacity())).id();
        eventService.setPricing(concertId, new SetEventPricingRequest(List.of(
                new PricingItem(SeatType.VIP, null, new BigDecimal("200.00")),
                new PricingItem(SeatType.PREMIUM, null, new BigDecimal("120.00")),
                new PricingItem(SeatType.REGULAR, null, new BigDecimal("60.00"))
        )));
        eventService.updateStatus(concertId, EventStatus.PUBLISHED);

        Long festivalId = eventService.create(new CreateEventRequest(
                "Summer Food Festival", "Open-air general admission festival.", "Festival",
                base.plus(1, ChronoUnit.DAYS), base.plus(1, ChronoUnit.DAYS).plus(8, ChronoUnit.HOURS),
                organizerId, openArena.id(), 300)).id();
        eventService.setPricing(festivalId, new SetEventPricingRequest(List.of(
                new PricingItem(null, null, new BigDecimal("50.00"))
        )));
        eventService.updateStatus(festivalId, EventStatus.PUBLISHED);

        log.info("Demo data seeded: seated event id={} , general-admission event id={}", concertId, festivalId);
    }
}
