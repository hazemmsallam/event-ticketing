package com.eventticketing.reservation;

import com.eventticketing.catalog.domain.SectionBookingMode;
import com.eventticketing.catalog.domain.SectionShape;
import com.eventticketing.catalog.dto.CreateEventRequest;
import com.eventticketing.catalog.dto.CreateHallRequest;
import com.eventticketing.catalog.dto.CreateOrganizerRequest;
import com.eventticketing.catalog.dto.HallResponse;
import com.eventticketing.catalog.dto.PricingItem;
import com.eventticketing.catalog.dto.SeatEditItem;
import com.eventticketing.catalog.dto.SectionItem;
import com.eventticketing.catalog.dto.SectionResponse;
import com.eventticketing.catalog.dto.SetEventPricingRequest;
import com.eventticketing.catalog.dto.UpdateHallSeatsRequest;
import com.eventticketing.catalog.domain.EventStatus;
import com.eventticketing.catalog.service.EventService;
import com.eventticketing.catalog.service.HallService;
import com.eventticketing.catalog.service.OrganizerService;
import com.eventticketing.common.exception.ConflictException;
import com.eventticketing.common.security.CustomerId;
import com.eventticketing.reservation.domain.BookingSeatStatus;
import com.eventticketing.reservation.dto.BookingResponse;
import com.eventticketing.reservation.dto.CreateBookingRequest;
import com.eventticketing.reservation.dto.GeneralAdmissionSelection;
import com.eventticketing.reservation.repository.BookingSeatRepository;
import com.eventticketing.reservation.service.PaymentService;
import com.eventticketing.reservation.service.ReservationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the inventory guards on real MySQL: concurrent buyers of the same seat or the last
 * general-admission tickets never oversell, and a mixed seats + GA order survives the full flow.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class ConcurrentBookingIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired OrganizerService organizerService;
    @Autowired HallService hallService;
    @Autowired EventService eventService;
    @Autowired ReservationService reservationService;
    @Autowired PaymentService paymentService;
    @Autowired BookingSeatRepository bookingSeatRepository;

    @Test
    void onlyOneBookingWinsTheSameSeat() throws Exception {
        Long organizerId = organizerService.create(
                new CreateOrganizerRequest("Acme Events", "acme@example.com", "+100")).id();

        HallResponse hall = hallService.create(new CreateHallRequest(
                "Small Hall", "1 Main St", true, 1, 1, null, null));
        Long seatId = hall.seats().get(0).id();
        Long eventId = publishedEvent(organizerId, hall, "Gala", "100.00");

        int successes = race(8, n -> reservationService.createBooking(
                new CreateBookingRequest(eventId, List.of(seatId), null), customer("user-" + n)));

        assertThat(successes).isEqualTo(1);
        assertThat(bookingSeatRepository.countByEventIdAndStatus(eventId, BookingSeatStatus.HELD)).isEqualTo(1);
    }

    /** GA tickets have no unique index; only the event lock stands between buyers and overselling. */
    @Test
    void lastGeneralAdmissionTicketsGoToExactlyAsManyBuyersAsThereAreTickets() throws Exception {
        Long organizerId = organizerService.create(
                new CreateOrganizerRequest("Rush Events", "rush@example.com", "+103")).id();
        HallResponse hall = hallService.create(new CreateHallRequest(
                "Rush Arena", "4 Main St", false, null, null, null, 3));
        Long gaSectionId = hall.sections().get(0).id();
        Long eventId = publishedEvent(organizerId, hall, "Rush Show", "10.00");

        int successes = race(10, n -> reservationService.createBooking(
                gaOnly(eventId, gaSectionId, 1), customer("rush-" + n)));

        assertThat(successes).isEqualTo(3);
        assertThatThrownBy(() -> reservationService.createBooking(gaOnly(eventId, gaSectionId, 1), customer("late")))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void mixedSeatsAndGeneralAdmissionOrderIsHeldEditedAndPaid() {
        Long organizerId = organizerService.create(
                new CreateOrganizerRequest("Mixed Events", "mixed@example.com", "+104")).id();
        HallResponse created = hallService.create(new CreateHallRequest(
                "Mixed Hall", "5 Main St", true, 1, 2, null, null));
        HallResponse hall = addGaSection(created, "Standing", 3);
        Long seatedSectionId = sectionId(hall, SectionBookingMode.SEATED);
        Long gaSectionId = sectionId(hall, SectionBookingMode.GENERAL_ADMISSION);
        assertThat(hall.capacity()).isEqualTo(5);

        Instant start = Instant.now().plus(3, ChronoUnit.DAYS);
        Long eventId = eventService.create(new CreateEventRequest(
                "Mixed Show", "desc", "Music", null, null, false, null,
                start, start.plus(2, ChronoUnit.HOURS),
                organizerId, hall.id(), null)).id();
        eventService.setPricing(eventId, new SetEventPricingRequest(List.of(
                new PricingItem(seatedSectionId, new BigDecimal("30.00")),
                new PricingItem(gaSectionId, new BigDecimal("10.00")))));
        eventService.updateStatus(eventId, EventStatus.PUBLISHED);
        assertThat(eventService.get(eventId).maxCapacity()).isEqualTo(5);

        Long firstSeat = hall.seats().get(0).id();
        Long secondSeat = hall.seats().get(1).id();
        CustomerId buyer = customer("mixed-buyer");

        BookingResponse held = reservationService.createBooking(new CreateBookingRequest(eventId,
                List.of(firstSeat), List.of(new GeneralAdmissionSelection(gaSectionId, 2))), buyer);
        assertThat(held.quantity()).isEqualTo(3);
        assertThat(held.totalAmount()).isEqualByComparingTo("53.00");

        BookingResponse edited = reservationService.changeItems(held.id(), buyer.ref(),
                List.of(secondSeat), List.of(new GeneralAdmissionSelection(gaSectionId, 3)));
        assertThat(edited.seats()).extracting(s -> s.seatId()).containsExactly(secondSeat);
        assertThat(edited.totalAmount()).isEqualByComparingTo("64.00");
        assertThat(edited.expiresAt()).isEqualTo(held.expiresAt());

        // All three GA tickets are held, so nobody else can get one, but the freed seat is open.
        assertThatThrownBy(() -> reservationService.createBooking(gaOnly(eventId, gaSectionId, 1), customer("other")))
                .isInstanceOf(ConflictException.class);
        assertThat(reservationService.createBooking(
                new CreateBookingRequest(eventId, List.of(firstSeat), null), customer("seat-buyer"))).isNotNull();

        assertThat(paymentService.pay(held.id(), buyer.ref()).success()).isTrue();
        BookingResponse paid = reservationService.getBooking(held.id(), buyer.ref());
        assertThat(paid.tickets()).hasSize(4);
        assertThat(paid.tickets()).filteredOn(t -> gaSectionId.equals(t.sectionId()) && t.seatId() == null).hasSize(3);
        assertThat(reservationService.listBookings(buyer.ref()).get(0).generalAdmission()).hasSize(1);
    }

    @Test
    void listsOnlyTheCustomersBookingsNewestFirst() {
        Long organizerId = organizerService.create(
                new CreateOrganizerRequest("History Events", "history@example.com", "+101")).id();
        HallResponse hall = hallService.create(new CreateHallRequest(
                "History Arena", "2 Main St", false, null, null, null, 10));
        Long gaSectionId = hall.sections().get(0).id();
        Long eventId = publishedEvent(organizerId, hall, "History Show", "25.00");

        CustomerId shopper = customer("history-user");
        CustomerId other = customer("other-user");

        BookingResponse first = reservationService.createBooking(gaOnly(eventId, gaSectionId, 1), shopper);
        // Only one live hold per customer is allowed, so release the first before booking again.
        reservationService.cancelBooking(first.id(), shopper.ref());
        reservationService.createBooking(gaOnly(eventId, gaSectionId, 1), other);
        BookingResponse second = reservationService.createBooking(gaOnly(eventId, gaSectionId, 2), shopper);

        List<BookingResponse> history = reservationService.listBookings(shopper.ref());

        assertThat(history).extracting(BookingResponse::id).containsExactly(second.id(), first.id());
        assertThat(history).extracting(BookingResponse::customerRef).containsOnly(shopper.ref());
        assertThat(history).extracting(BookingResponse::eventName).containsOnly("History Show");
    }

    /**
     * The seat-squatting control: one customer may only park inventory once at a time, so an
     * abuser cannot accumulate holds across events by repeating the call.
     */
    @Test
    void rejectsASecondHoldWhileTheFirstIsStillLive() {
        Long organizerId = organizerService.create(
                new CreateOrganizerRequest("Quota Events", "quota@example.com", "+102")).id();
        HallResponse hall = hallService.create(new CreateHallRequest(
                "Quota Arena", "3 Main St", false, null, null, null, 20));
        Long gaSectionId = hall.sections().get(0).id();
        Long eventId = publishedEvent(organizerId, hall, "Quota Show", "15.00");

        CustomerId squatter = customer("squatter");
        reservationService.createBooking(gaOnly(eventId, gaSectionId, 1), squatter);

        assertThatThrownBy(() -> reservationService.createBooking(gaOnly(eventId, gaSectionId, 1), squatter))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already have seats on hold");

        // A different customer is unaffected — the quota is per identity, not global.
        assertThat(reservationService.createBooking(gaOnly(eventId, gaSectionId, 1), customer("someone-else")))
                .isNotNull();
    }

    /** An event on a single-section hall, priced and published. */
    private Long publishedEvent(Long organizerId, HallResponse hall, String name, String price) {
        Instant start = Instant.now().plus(2, ChronoUnit.DAYS);
        Long eventId = eventService.create(new CreateEventRequest(
                name, "desc", "Music", null, null, false, null,
                start, start.plus(2, ChronoUnit.HOURS),
                organizerId, hall.id(), null)).id();
        eventService.setPricing(eventId, new SetEventPricingRequest(List.of(
                new PricingItem(hall.sections().get(0).id(), new BigDecimal(price)))));
        eventService.updateStatus(eventId, EventStatus.PUBLISHED);
        return eventId;
    }

    private static CreateBookingRequest gaOnly(Long eventId, Long sectionId, int quantity) {
        return new CreateBookingRequest(eventId, null, List.of(new GeneralAdmissionSelection(sectionId, quantity)));
    }

    /** Keeps the seats and seated section of a hall as they are and adds one GA section. */
    private HallResponse addGaSection(HallResponse hall, String name, int capacity) {
        List<SeatEditItem> seats = hall.seats().stream()
                .map(s -> new SeatEditItem(s.id(), s.label(), s.rowLabel(), s.rowIndex(), s.seatNumber(),
                        s.layoutX(), s.layoutY(), s.rotationDegrees(), s.layoutWidth(), s.layoutHeight(),
                        s.sectionName()))
                .toList();
        List<SectionItem> sections = new ArrayList<>();
        for (SectionResponse s : hall.sections()) {
            sections.add(new SectionItem(s.id(), s.name(), s.bookingMode(), s.capacity(),
                    s.shapeKind(), s.points(), null, s.color()));
        }
        sections.add(new SectionItem(null, name, SectionBookingMode.GENERAL_ADMISSION, capacity,
                SectionShape.RECTANGLE, List.of(), null, "#22C55E"));
        return hallService.updateSeats(hall.id(), new UpdateHallSeatsRequest(
                hall.layoutWidth(), hall.layoutHeight(), seats, null, sections));
    }

    private static Long sectionId(HallResponse hall, SectionBookingMode mode) {
        return hall.sections().stream().filter(s -> s.bookingMode() == mode).findFirst().orElseThrow().id();
    }

    private interface Attempt {
        void run(int n) throws Exception;
    }

    /**
     * Releases {@code threads} attempts at once and returns how many succeeded. Losing must be a
     * clean {@link ConflictException}; anything else fails the test instead of passing as a loss.
     */
    private static int race(int threads, Attempt attempt) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger success = new AtomicInteger();
        List<Throwable> unexpected = java.util.Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < threads; i++) {
            final int n = i;
            pool.submit(() -> {
                try {
                    startGate.await();
                    attempt.run(n);
                    success.incrementAndGet();
                } catch (ConflictException expected) {
                    // lost the race cleanly
                } catch (Throwable e) {
                    unexpected.add(e);
                } finally {
                    done.countDown();
                }
            });
        }
        startGate.countDown();
        done.await();
        pool.shutdown();
        assertThat(unexpected).as("losers must fail with a ConflictException only").isEmpty();
        return success.get();
    }

    /** Mirrors how TokenAuthenticator derives a stable id, so tests share one identity model. */
    private static CustomerId customer(String token) {
        return null;
    }
}
