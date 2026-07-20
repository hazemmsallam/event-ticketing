package com.eventticketing.reservation;

import com.eventticketing.catalog.dto.CreateEventRequest;
import com.eventticketing.catalog.dto.CreateHallRequest;
import com.eventticketing.catalog.dto.CreateOrganizerRequest;
import com.eventticketing.catalog.dto.HallResponse;
import com.eventticketing.catalog.dto.PricingItem;
import com.eventticketing.catalog.dto.SetEventPricingRequest;
import com.eventticketing.catalog.domain.EventStatus;
import com.eventticketing.catalog.domain.SeatType;
import com.eventticketing.catalog.service.EventService;
import com.eventticketing.catalog.service.HallService;
import com.eventticketing.catalog.service.OrganizerService;
import com.eventticketing.common.exception.ConflictException;
import com.eventticketing.reservation.domain.BookingSeatStatus;
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

/**
 * Proves that concurrent attempts to book the same seat result in exactly one success,
 * with the database unique index rejecting the rest.
 */
@SpringBootTest
@Testcontainers
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
                "Small Hall", "1 Main St", true, 1, 1, null, List.of(), null));
        Long hallId = hall.id();
        Long seatId = hall.seats().get(0).id();

        Instant start = Instant.now().plus(1, ChronoUnit.DAYS);
        Long eventId = eventService.create(new CreateEventRequest(
                "Gala", "desc", "Music", start, start.plus(2, ChronoUnit.HOURS),
                organizerId, hallId, 1)).id();
        eventService.setPricing(eventId,
                new SetEventPricingRequest(List.of(new PricingItem(SeatType.REGULAR, new BigDecimal("100.00")))));
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
                            new CreateBookingRequest(eventId, "user-" + n, List.of(seatId), null));
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
}
