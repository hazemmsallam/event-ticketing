package com.eventticketing.reservation;

import com.eventticketing.catalog.domain.*;
import com.eventticketing.catalog.repository.*;
import com.eventticketing.common.exception.BusinessRuleException;
import com.eventticketing.common.exception.ConflictException;
import com.eventticketing.common.security.CustomerId;
import com.eventticketing.common.security.UnpaidHoldThrottle;
import com.eventticketing.payment.PaymentResult;
import com.eventticketing.reservation.config.ReservationProperties;
import com.eventticketing.reservation.domain.*;
import com.eventticketing.reservation.dto.*;
import com.eventticketing.reservation.repository.*;
import com.eventticketing.reservation.service.ReservationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.cache.CacheManager;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Business-flow tests; database atomicity and locking are covered on MySQL separately. */
class MixedBookingServiceTest {
    private static final long EVENT = 100L;
    private static final long SEATED = 10L;
    private static final long STANDING = 20L;
    private static final long VIP_STANDING = 21L;

    private final BookingRepository bookings = proxy(BookingRepository.class);
    private final BookingSeatRepository bookingSeats = proxy(BookingSeatRepository.class);
    private final PaymentRepository payments = proxy(PaymentRepository.class);
    private final EventRepository events = proxy(EventRepository.class);
    private final SeatRepository seats = proxy(SeatRepository.class);
    private final CustomerId customer = new CustomerId(UUID.randomUUID());
    private final Instant now = Instant.parse("2026-09-29T12:00:00Z");
    private final Hall hall = new Hall();
    private final Event event = new Event();
    private final Section seated = section(SEATED, "Seats", SectionBookingMode.SEATED, null);
    private final Section standing = section(STANDING, "Standing", SectionBookingMode.GENERAL_ADMISSION, 10);
    private final Section vipStanding = section(VIP_STANDING, "VIP Standing", SectionBookingMode.GENERAL_ADMISSION, 5);
    private final Map<Long, Seat> seatById = new HashMap<>();
    private ReservationService service;
    private Booking saved;
    private Payment payment;

    private static <T> T proxy(Class<T> type) {
        return mock(type, withSettings().mockMaker(org.mockito.MockMakers.PROXY));
    }

    private static Section section(long id, String name, SectionBookingMode mode, Integer capacity) {
        Section section = new Section();
        section.setId(id);
        section.setName(name);
        section.setBookingMode(mode);
        section.setCapacity(capacity);
        section.setCurrency("JOD");
        section.setPoints("[]");
        return section;
    }

    @BeforeEach
    void setUp() {
        hall.setId(1L);
        hall.setName("Mixed Hall");
        hall.setSeated(true);
        hall.setCapacity(25);
        hall.addSection(seated);
        hall.addSection(standing);
        hall.addSection(vipStanding);
        event.setId(EVENT);
        event.setName("Show");
        event.setHall(hall);
        event.setMaxCapacity(25);
        event.setStatus(EventStatus.PUBLISHED);
        event.addPricing(new EventPricing(seated, new BigDecimal("20.00")));
        event.addPricing(new EventPricing(standing, new BigDecimal("5.00")));
        event.addPricing(new EventPricing(vipStanding, new BigDecimal("12.00")));
        for (long id = 1; id <= 10; id++) {
            Seat seat = new Seat();
            seat.setId(id);
            seat.setLabel("A" + id);
            seat.setSection(seated);
            hall.addSeat(seat);
            seatById.put(id, seat);
        }
        when(events.findByIdForUpdate(EVENT)).thenReturn(Optional.of(event));
        when(events.findById(EVENT)).thenReturn(Optional.of(event));
        when(seats.findAllById(any())).thenAnswer(call -> {
            List<Seat> found = new ArrayList<>();
            for (Long id : call.<Iterable<Long>>getArgument(0)) {
                if (seatById.containsKey(id)) found.add(seatById.get(id));
            }
            return found;
        });
        when(bookings.saveAndFlush(any())).thenAnswer(call -> {
            saved = call.getArgument(0);
            saved.setId(1L);
            return saved;
        });
        when(bookings.findEventId(1L)).thenReturn(Optional.of(EVENT));
        when(bookings.findByIdForUpdate(1L)).thenAnswer(call -> Optional.ofNullable(saved));
        when(payments.findByBookingId(1L)).thenAnswer(call -> Optional.ofNullable(payment));
        when(payments.saveAndFlush(any())).thenAnswer(call -> {
            payment = call.getArgument(0);
            payment.setId(1L);
            return payment;
        });
        when(payments.findById(1L)).thenAnswer(call -> Optional.ofNullable(payment));
        when(payments.findEventId(1L)).thenReturn(Optional.of(EVENT));
        ReservationProperties properties = new ReservationProperties(Duration.ofMinutes(10), 10,
                Duration.ofSeconds(30), Duration.ofSeconds(2), Duration.ofMinutes(1), 5, Duration.ofMinutes(10));
        UnpaidHoldThrottle throttle = new UnpaidHoldThrottle(null, properties) {
            @Override public void onPaymentConfirmed(String ref) { }
        };
        service = new ReservationService(bookings, bookingSeats, payments, events, seats,
                proxy(TicketRepository.class), properties, Clock.fixed(now, ZoneOffset.UTC),
                proxy(CacheManager.class), new ObjectMapper(), throttle);
    }

    private static GeneralAdmissionSelection ga(long sectionId, Integer quantity) {
        return new GeneralAdmissionSelection(sectionId, quantity);
    }

    private BookingResponse book(List<Long> seatIds, GeneralAdmissionSelection... generalAdmission) {
        return service.createBooking(new CreateBookingRequest(EVENT, seatIds, List.of(generalAdmission)), customer);
    }

    /** Two seats (2 x 20) + 3 standing (3 x 5) + 1 VIP standing (1 x 12) = 67.00, 6 tickets. */
    private BookingResponse mixed() {
        return book(List.of(1L, 2L), ga(STANDING, 3), ga(VIP_STANDING, 1));
    }

    private GeneralAdmissionResponse line(BookingResponse response, long sectionId) {
        return response.generalAdmission().stream()
                .filter(l -> l.sectionId() == sectionId).findFirst().orElseThrow();
    }

    // ------------------------------------------------------------------ create

    @Test
    void mixedOrderHasOneLinePerGaSectionAndOneCombinedTotal() {
        BookingResponse response = mixed();
        assertThat(response.quantity()).isEqualTo(6);
        assertThat(response.totalAmount()).isEqualByComparingTo("67.00");
        assertThat(response.seats()).hasSize(2);
        assertThat(response.generalAdmission()).hasSize(2);
        assertThat(line(response, STANDING).quantity()).isEqualTo(3);
        assertThat(line(response, STANDING).totalAmount()).isEqualByComparingTo("15.00");
        assertThat(line(response, VIP_STANDING).unitPrice()).isEqualByComparingTo("12.00");
        assertThat(response.tickets()).isEmpty();
    }

    @Test
    void seatOnlyOrderHasNoGaLines() {
        BookingResponse response = book(List.of(1L));
        assertThat(response.quantity()).isEqualTo(1);
        assertThat(response.totalAmount()).isEqualByComparingTo("20.00");
        assertThat(response.generalAdmission()).isEmpty();
    }

    @Test
    void gaOnlyOrderMayUseSeveralSections() {
        BookingResponse response = book(null, ga(STANDING, 2), ga(VIP_STANDING, 2));
        assertThat(response.quantity()).isEqualTo(4);
        assertThat(response.seats()).isEmpty();
        assertThat(response.totalAmount()).isEqualByComparingTo("34.00");
    }

    static Stream<CreateBookingRequest> invalidRequests() {
        return Stream.of(
                // more than 10 tickets in total, counting seats and GA together
                new CreateBookingRequest(EVENT, List.of(1L), List.of(ga(STANDING, 5), ga(VIP_STANDING, 5))),
                new CreateBookingRequest(EVENT, List.of(1L), List.of(ga(STANDING, null))),
                new CreateBookingRequest(EVENT, List.of(1L), List.of(ga(STANDING, 0))),
                new CreateBookingRequest(EVENT, List.of(1L), List.of(ga(STANDING, -1))),
                new CreateBookingRequest(EVENT, List.of(1L), Collections.singletonList(null)),
                new CreateBookingRequest(EVENT, List.of(1L), List.of(new GeneralAdmissionSelection(null, 1))),
                new CreateBookingRequest(EVENT, List.of(1L, 1L), List.of()),
                new CreateBookingRequest(EVENT, null, null),
                new CreateBookingRequest(EVENT, List.of(), List.of()),
                // a seated section cannot be bought by quantity
                new CreateBookingRequest(EVENT, List.of(1L), List.of(ga(SEATED, 1))),
                // the same GA section twice
                new CreateBookingRequest(EVENT, null, List.of(ga(STANDING, 1), ga(STANDING, 2))),
                // a section of another hall
                new CreateBookingRequest(EVENT, null, List.of(ga(999L, 1))));
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    void invalidOrAmbiguousInputIsRejected(CreateBookingRequest request) {
        assertThatThrownBy(() -> service.createBooking(request, customer))
                .isInstanceOf(BusinessRuleException.class);
        verify(bookings, never()).saveAndFlush(any());
    }

    @Test
    void unpublishedEventIsRejected() {
        event.setStatus(EventStatus.DRAFT);
        assertThatThrownBy(this::mixed).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void fullGaSectionRejectsTheEntireSelection() {
        when(bookings.sumConfirmedQuantityBySection(EVENT, VIP_STANDING)).thenReturn(4L);
        when(bookings.sumReservedQuantityBySection(EVENT, VIP_STANDING, now)).thenReturn(1L);
        assertThatThrownBy(this::mixed).isInstanceOf(ConflictException.class)
                .hasMessageContaining("VIP Standing");
        verify(bookings, never()).saveAndFlush(any());
    }

    @Test
    void gaCapacityIsCountedPerEventAndSection() {
        mixed();
        verify(bookings).sumConfirmedQuantityBySection(EVENT, STANDING);
        verify(bookings).sumReservedQuantityBySection(EVENT, VIP_STANDING, now);
    }

    @Test
    void occupiedSeatRejectsTheEntireSelection() {
        BookingSeat taken = new BookingSeat();
        Booking other = new Booking();
        other.setId(55L);
        taken.setBooking(other);
        taken.setSeat(seatById.get(1L));
        when(bookingSeats.findForSeats(eq(EVENT), any(), any())).thenReturn(List.of(taken));
        assertThatThrownBy(this::mixed).isInstanceOf(ConflictException.class).hasMessageContaining("A1");
        verify(bookings, never()).saveAndFlush(any());
    }

    @Test
    void differentCurrenciesCannotShareAnOrder() {
        vipStanding.setCurrency("USD");
        assertThatThrownBy(this::mixed).isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("same currency");
    }

    // ------------------------------------------------------------------ edit

    @Test
    void editCanIncreaseRemoveAndAddGaSectionsAndSwapSeats() {
        mixed();
        Instant expiresAt = saved.getExpiresAt();
        // A later price change must not reprice the line that is kept.
        event.getPricing().get(1).setPrice(new BigDecimal("99.00"));

        BookingResponse response = service.changeItems(1L, customer.ref(), List.of(3L), List.of(ga(STANDING, 5)));

        assertThat(response.seats()).extracting(BookingSeatResponse::seatId).containsExactly(3L);
        assertThat(response.generalAdmission()).hasSize(1);
        assertThat(line(response, STANDING).quantity()).isEqualTo(5);
        assertThat(line(response, STANDING).unitPrice()).isEqualByComparingTo("5.00");
        assertThat(response.quantity()).isEqualTo(6);
        assertThat(response.totalAmount()).isEqualByComparingTo("45.00");
        assertThat(response.expiresAt()).isEqualTo(expiresAt);
        assertThat(saved.getBookingSeats()).filteredOn(bs -> bs.getStatus() == BookingSeatStatus.RELEASED)
                .extracting(bs -> bs.getSeat().getId()).containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    void editCanRemoveEveryGaTicketOrEverySeat() {
        mixed();
        BookingResponse seatsOnly = service.changeItems(1L, customer.ref(), List.of(1L), null);
        assertThat(seatsOnly.generalAdmission()).isEmpty();
        assertThat(seatsOnly.totalAmount()).isEqualByComparingTo("20.00");

        BookingResponse gaOnly = service.changeItems(1L, customer.ref(), List.of(), List.of(ga(VIP_STANDING, 2)));
        assertThat(gaOnly.seats()).isEmpty();
        assertThat(gaOnly.totalAmount()).isEqualByComparingTo("24.00");
    }

    @Test
    void editGivesBackItsOwnHeldTicketsWhenCheckingCapacity() {
        mixed();
        // VIP has 5; this booking holds 1 of the 4 reserved. Going from 1 to 2 fits exactly.
        when(bookings.sumReservedQuantityBySection(EVENT, VIP_STANDING, now)).thenReturn(4L);
        BookingResponse response = service.changeItems(1L, customer.ref(), List.of(1L, 2L),
                List.of(ga(STANDING, 3), ga(VIP_STANDING, 2)));
        assertThat(line(response, VIP_STANDING).quantity()).isEqualTo(2);
    }

    @Test
    void editBeyondSectionCapacityChangesNothing() {
        mixed();
        when(bookings.sumReservedQuantityBySection(EVENT, VIP_STANDING, now)).thenReturn(5L);
        assertThatThrownBy(() -> service.changeItems(1L, customer.ref(), List.of(3L),
                List.of(ga(STANDING, 3), ga(VIP_STANDING, 2)))).isInstanceOf(ConflictException.class);
        assertThat(saved.heldSeats()).extracting(bs -> bs.getSeat().getId()).containsExactly(1L, 2L);
        assertThat(saved.generalAdmissionQuantity()).isEqualTo(4);
        assertThat(saved.getTotalAmount()).isEqualByComparingTo("67.00");
    }

    @Test
    void editIncludesGaTicketsInTheLimit() {
        mixed();
        assertThatThrownBy(() -> service.changeItems(1L, customer.ref(),
                List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L), List.of(ga(STANDING, 3), ga(VIP_STANDING, 1))))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void seatOnlyEditKeepsGaTickets() {
        mixed();
        BookingResponse response = service.changeSeats(1L, customer.ref(), List.of(3L));
        assertThat(response.generalAdmission()).hasSize(2);
        assertThat(response.quantity()).isEqualTo(5);
        assertThat(response.totalAmount()).isEqualByComparingTo("47.00");
    }

    @Test
    void pendingPaymentBlocksEdits() {
        mixed();
        service.beginPayment(1L, customer.ref());
        assertThatThrownBy(() -> service.changeItems(1L, customer.ref(), List.of(3L), null))
                .isInstanceOf(ConflictException.class);
        assertThat(saved.getTotalAmount()).isEqualByComparingTo("67.00");
    }

    // ------------------------------------------------------------------ pay, cancel, expire

    @Test
    void paymentIssuesEveryTicketExactlyOnce() {
        mixed();
        assertThat(service.beginPayment(1L, customer.ref()).amount()).isEqualByComparingTo("67.00");
        service.applyPaymentResult(1L, PaymentResult.ok("charge-1"));
        service.applyPaymentResult(1L, PaymentResult.ok("charge-1"));
        assertThat(saved.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(saved.getTickets()).hasSize(6);
        assertThat(saved.getTickets()).filteredOn(t -> t.getSeatId() != null).hasSize(2);
        assertThat(saved.getTickets()).filteredOn(t -> Objects.equals(t.getSectionId(), STANDING)).hasSize(3);
        assertThat(saved.getTickets()).filteredOn(t -> Objects.equals(t.getSectionId(), VIP_STANDING)).hasSize(1);
        assertThat(saved.getTickets()).extracting(Ticket::getTicketNumber).doesNotHaveDuplicates();
    }

    @Test
    void reconciliationAlsoIssuesEveryTicket() {
        mixed();
        service.beginPayment(1L, customer.ref());
        service.reconcile(1L, Optional.of(PaymentResult.ok("charge-1")));
        assertThat(saved.getTickets()).hasSize(6);
    }

    @Test
    void mismatchedChargeCannotConfirmTheOrder() {
        mixed();
        service.beginPayment(1L, customer.ref());
        payment.setAmount(BigDecimal.ONE);
        assertThatThrownBy(() -> service.applyPaymentResult(1L, PaymentResult.ok("bad-charge")))
                .isInstanceOf(ConflictException.class);
        assertThat(saved.getTickets()).isEmpty();
        assertThat(saved.getStatus()).isEqualTo(BookingStatus.PENDING_PAYMENT);
    }

    @Test
    void cancellationReleasesSeatsAndKeepsGaHistory() {
        mixed();
        service.cancelBooking(1L, customer.ref());
        assertThat(saved.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(saved.getBookingSeats()).allSatisfy(line ->
                assertThat(line.getStatus()).isEqualTo(BookingSeatStatus.RELEASED));
        assertThat(saved.generalAdmissionQuantity()).isEqualTo(4);
    }

    @Test
    void expiryReleasesSeatsAndMarksTheWholeOrderExpired() {
        mixed();
        saved.setExpiresAt(now.minusSeconds(1));
        when(bookings.findByStatusAndExpiresAtBeforeOrderByExpiresAtAsc(eq(BookingStatus.PENDING_PAYMENT),
                eq(now), any())).thenReturn(List.of(saved));
        assertThat(service.releaseExpired()).isEqualTo(1);
        assertThat(saved.getStatus()).isEqualTo(BookingStatus.EXPIRED);
        assertThat(saved.getBookingSeats()).allSatisfy(line ->
                assertThat(line.getStatus()).isEqualTo(BookingSeatStatus.RELEASED));
    }
}
