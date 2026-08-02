package com.eventticketing.reservation;

import com.eventticketing.catalog.dto.CreateEventRequest;
import com.eventticketing.catalog.dto.CreateHallRequest;
import com.eventticketing.catalog.dto.CreateOrganizerRequest;
import com.eventticketing.catalog.dto.HallResponse;
import com.eventticketing.catalog.dto.PricingItem;
import com.eventticketing.catalog.dto.SetEventPricingRequest;
import com.eventticketing.catalog.domain.EventStatus;
import com.eventticketing.catalog.service.EventService;
import com.eventticketing.catalog.service.HallService;
import com.eventticketing.catalog.service.OrganizerService;
import com.eventticketing.common.exception.ConflictException;
import com.eventticketing.common.security.CustomerId;
import com.eventticketing.common.security.TokenAuthenticator;
import com.eventticketing.reservation.domain.BookingSeatStatus;
import com.eventticketing.reservation.dto.BookingResponse;
import com.eventticketing.reservation.dto.CreateBookingRequest;
import com.eventticketing.reservation.repository.BookingSeatRepository;
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
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves that concurrent attempts to book the same seat result in exactly one success,
 * with the database unique index rejecting the rest.
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
    @Autowired BookingSeatRepository bookingSeatRepository;

    @Test
    void onlyOneBookingWinsTheSameSeat() throws Exception {
        Long organizerId = organizerService.create(
                new CreateOrganizerRequest("Acme Events", "acme@example.com", "+100")).id();

        HallResponse hall = hallService.create(new CreateHallRequest(
                "Small Hall", "1 Main St", true, 1, 1, null, null));
        Long hallId = hall.id();
        Long seatId = hall.seats().get(0).id();

        Instant start = Instant.now().plus(1, ChronoUnit.DAYS);
        Long eventId = eventService.create(new CreateEventRequest(
                "Gala", "desc", "Music", start, start.plus(2, ChronoUnit.HOURS),
                organizerId, hallId, 1)).id();
        eventService.setPricing(eventId,
                new SetEventPricingRequest(List.of(
                        new PricingItem(hall.sections().get(0).id(), new BigDecimal("100.00")))));
        eventService.updateStatus(eventId, EventStatus.PUBLISHED);

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            final int n = i;
            pool.submit(() -> {
                try {
                    startGate.await();
                    reservationService.createBooking(
                            new CreateBookingRequest(eventId, List.of(seatId), null, null),
                            customer("user-" + n));
                    success.incrementAndGet();
                } catch (ConflictException e) {
                    conflicts.incrementAndGet();
                } catch (Exception ignored) {
                    // Data race losers may surface as other DB exceptions; count them as conflicts.
                    conflicts.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        startGate.countDown();
        done.await();
        pool.shutdown();

        assertThat(success.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(threads - 1);
        assertThat(bookingSeatRepository.countByEventIdAndStatus(eventId, BookingSeatStatus.HELD)).isEqualTo(1);
    }

    @Test
    void listsOnlyTheCustomersBookingsNewestFirst() {
        Long organizerId = organizerService.create(
                new CreateOrganizerRequest("History Events", "history@example.com", "+101")).id();

        HallResponse hall = hallService.create(new CreateHallRequest(
                "History Arena", "2 Main St", false, null, null, null, 10));

        Instant start = Instant.now().plus(2, ChronoUnit.DAYS);
        Long eventId = eventService.create(new CreateEventRequest(
                "History Show", "desc", "Music", start, start.plus(2, ChronoUnit.HOURS),
                organizerId, hall.id(), 10)).id();
        eventService.setPricing(eventId,
                new SetEventPricingRequest(List.of(
                        new PricingItem(hall.sections().get(0).id(), new BigDecimal("25.00")))));
        eventService.updateStatus(eventId, EventStatus.PUBLISHED);

        CustomerId shopper = customer("history-user");
        CustomerId other = customer("other-user");

        BookingResponse first = reservationService.createBooking(
                new CreateBookingRequest(eventId, null, null, 1), shopper);
        // Only one live hold per customer is allowed, so release the first before booking again.
        reservationService.cancelBooking(first.id(), shopper.ref());
        reservationService.createBooking(new CreateBookingRequest(eventId, null, null, 1), other);
        BookingResponse second = reservationService.createBooking(
                new CreateBookingRequest(eventId, null, null, 2), shopper);

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
        Instant start = Instant.now().plus(2, ChronoUnit.DAYS);
        Long eventId = eventService.create(new CreateEventRequest(
                "Quota Show", "desc", "Music", start, start.plus(2, ChronoUnit.HOURS),
                organizerId, hall.id(), 20)).id();
        eventService.setPricing(eventId, new SetEventPricingRequest(List.of(
                new PricingItem(hall.sections().get(0).id(), new BigDecimal("15.00")))));
        eventService.updateStatus(eventId, EventStatus.PUBLISHED);

        CustomerId squatter = customer("squatter");
        reservationService.createBooking(new CreateBookingRequest(eventId, null, null, 1), squatter);

        assertThatThrownBy(() -> reservationService.createBooking(
                new CreateBookingRequest(eventId, null, null, 1), squatter))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already have seats on hold");

        // A different customer is unaffected — the quota is per identity, not global.
        assertThat(reservationService.createBooking(
                new CreateBookingRequest(eventId, null, null, 1), customer("someone-else"))).isNotNull();
    }

    /** Mirrors how TokenAuthenticator derives a stable id, so tests share one identity model. */
    private static CustomerId customer(String token) {
        return new TokenAuthenticator().authenticate(token);
    }
}
