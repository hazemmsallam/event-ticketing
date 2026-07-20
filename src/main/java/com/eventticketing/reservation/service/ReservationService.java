package com.eventticketing.reservation.service;

import com.eventticketing.catalog.domain.Event;
import com.eventticketing.catalog.domain.EventPricing;
import com.eventticketing.catalog.domain.EventStatus;
import com.eventticketing.catalog.domain.Hall;
import com.eventticketing.catalog.domain.Seat;
import com.eventticketing.catalog.domain.SeatType;
import com.eventticketing.catalog.repository.EventRepository;
import com.eventticketing.catalog.repository.SeatRepository;
import com.eventticketing.common.exception.BusinessRuleException;
import com.eventticketing.common.exception.ConflictException;
import com.eventticketing.common.exception.ResourceNotFoundException;
import com.eventticketing.payment.PaymentResult;
import com.eventticketing.reservation.config.CacheNames;
import com.eventticketing.reservation.config.ReservationProperties;
import com.eventticketing.reservation.domain.Booking;
import com.eventticketing.reservation.domain.BookingSeat;
import com.eventticketing.reservation.domain.BookingSeatStatus;
import com.eventticketing.reservation.domain.BookingStatus;
import com.eventticketing.reservation.domain.Payment;
import com.eventticketing.reservation.domain.PaymentStatus;
import com.eventticketing.reservation.domain.SeatAvailabilityStatus;
import com.eventticketing.reservation.dto.BookingResponse;
import com.eventticketing.reservation.dto.CreateBookingRequest;
import com.eventticketing.reservation.dto.EventAvailabilityResponse;
import com.eventticketing.reservation.dto.EventSeatMapResponse;
import com.eventticketing.reservation.dto.PaymentResponse;
import com.eventticketing.reservation.dto.SeatAvailabilityResponse;
import com.eventticketing.reservation.repository.BookingRepository;
import com.eventticketing.reservation.repository.BookingSeatRepository;
import com.eventticketing.reservation.repository.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class ReservationService {

    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);

    private static final List<BookingSeatStatus> ACTIVE_SEAT_STATUSES =
            List.of(BookingSeatStatus.HELD, BookingSeatStatus.BOOKED);

    private final BookingRepository bookingRepository;
    private final BookingSeatRepository bookingSeatRepository;
    private final PaymentRepository paymentRepository;
    private final EventRepository eventRepository;
    private final SeatRepository seatRepository;
    private final ReservationProperties properties;
    private final Clock clock;
    private final CacheManager cacheManager;

    public ReservationService(BookingRepository bookingRepository,
                              BookingSeatRepository bookingSeatRepository,
                              PaymentRepository paymentRepository,
                              EventRepository eventRepository,
                              SeatRepository seatRepository,
                              ReservationProperties properties,
                              Clock clock,
                              CacheManager cacheManager) {
        this.bookingRepository = bookingRepository;
        this.bookingSeatRepository = bookingSeatRepository;
        this.paymentRepository = paymentRepository;
        this.eventRepository = eventRepository;
        this.seatRepository = seatRepository;
        this.properties = properties;
        this.clock = clock;
        this.cacheManager = cacheManager;
    }

    // ------------------------------------------------------------------ booking

    @Transactional
    public BookingResponse createBooking(CreateBookingRequest request) {
        Instant now = clock.instant();
        Event event = eventRepository.findById(request.eventId())
                .orElseThrow(() -> ResourceNotFoundException.of("Event", request.eventId()));

        if (event.getStatus() != EventStatus.PUBLISHED) {
            throw new BusinessRuleException(
                    "Event is not open for booking (status " + event.getStatus() + ").");
        }

        return event.getHall().isSeated()
                ? bookSeats(event, request, now)
                : bookGeneralAdmission(event, request, now);
    }

    private BookingResponse bookSeats(Event event, CreateBookingRequest request, Instant now) {
        List<Long> requested = request.seatIds();
        if (requested == null || requested.isEmpty()) {
            throw new BusinessRuleException("A seated event requires seatIds.");
        }
        Set<Long> seatIds = new LinkedHashSet<>(requested);
        if (seatIds.size() != requested.size()) {
            throw new BusinessRuleException("Duplicate seat ids in request.");
        }
        int max = properties.maxSeatsPerBooking();
        if (seatIds.size() > max) {
            throw new BusinessRuleException("You can book at most " + max + " seats per booking.");
        }

        // Free any seats whose holds have already expired so they become bookable again.
        // Flush the releases now: Hibernate orders INSERTs before UPDATEs within one flush, so
        // the release (UPDATE -> active_lock NULL) must reach the DB before the new hold's
        // INSERT, otherwise the insert would collide with the stale active_lock.
        releaseExpiredForEvent(event.getId(), now);
        bookingRepository.flush();

        List<Seat> seats = seatRepository.findAllById(seatIds);
        if (seats.size() != seatIds.size()) {
            throw new ResourceNotFoundException("One or more requested seats do not exist.");
        }
        Long hallId = event.getHall().getId();
        for (Seat seat : seats) {
            if (!seat.getHall().getId().equals(hallId)) {
                throw new BusinessRuleException(
                        "Seat " + seat.getLabel() + " does not belong to this event's hall.");
            }
        }

        // Pre-check for a friendly error; the DB unique index is the real guard against races.
        List<BookingSeat> alreadyActive =
                bookingSeatRepository.findForSeats(event.getId(), seatIds, ACTIVE_SEAT_STATUSES);
        if (!alreadyActive.isEmpty()) {
            String taken = alreadyActive.stream()
                    .map(bs -> bs.getSeat().getLabel())
                    .distinct()
                    .collect(Collectors.joining(", "));
            throw new ConflictException("These seats are already reserved or booked: " + taken + ".");
        }

        Map<SeatType, BigDecimal> priceByType = pricingByType(event);

        Booking booking = newBooking(event, request.customerRef(), seats.size(), now);
        BigDecimal total = BigDecimal.ZERO;
        for (Seat seat : seats) {
            BigDecimal price = priceByType.get(seat.getSeatType());
            if (price == null) {
                throw new BusinessRuleException(
                        "No price configured for seat type " + seat.getSeatType() + ".");
            }
            BookingSeat bookingSeat = new BookingSeat();
            bookingSeat.setEvent(event);
            bookingSeat.setSeat(seat);
            bookingSeat.setSeatType(seat.getSeatType());
            bookingSeat.setPrice(price);
            bookingSeat.setStatus(BookingSeatStatus.HELD);
            booking.addBookingSeat(bookingSeat);
            total = total.add(price);
        }
        booking.setTotalAmount(total);

        try {
            bookingRepository.saveAndFlush(booking);
        } catch (DataIntegrityViolationException ex) {
            // Lost the race on the active-seat unique index.
            throw new ConflictException(
                    "One or more selected seats were just taken. Please choose different seats.");
        }
        evictAfterCommit(event.getId());
        return BookingResponse.from(booking);
    }

    private BookingResponse bookGeneralAdmission(Event event, CreateBookingRequest request, Instant now) {
        Integer quantity = request.quantity();
        if (quantity == null || quantity < 1) {
            throw new BusinessRuleException("A non-seated event requires a quantity of at least 1.");
        }
        int max = properties.maxSeatsPerBooking();
        if (quantity > max) {
            throw new BusinessRuleException("You can book at most " + max + " tickets per booking.");
        }

        // Serialize capacity checks for this event so it cannot be oversold.
        Event locked = eventRepository.findByIdForUpdate(event.getId())
                .orElseThrow(() -> ResourceNotFoundException.of("Event", event.getId()));

        releaseExpiredForEvent(locked.getId(), now);
        bookingRepository.flush();

        long confirmed = bookingRepository.sumConfirmedQuantity(locked.getId());
        long reserved = bookingRepository.sumReservedQuantity(locked.getId(), now);
        long available = locked.getMaxCapacity() - (confirmed + reserved);
        if (quantity > available) {
            throw new ConflictException("Not enough capacity: only " + Math.max(available, 0) + " left.");
        }

        BigDecimal price = generalAdmissionPrice(locked);
        if (price == null) {
            throw new BusinessRuleException("No general-admission price configured for this event.");
        }

        Booking booking = newBooking(locked, request.customerRef(), quantity, now);
        booking.setTotalAmount(price.multiply(BigDecimal.valueOf(quantity)));
        bookingRepository.save(booking);
        evictAfterCommit(locked.getId());
        return BookingResponse.from(booking);
    }

    private Booking newBooking(Event event, String customerRef, int quantity, Instant now) {
        Booking booking = new Booking();
        booking.setEvent(event);
        booking.setCustomerRef(customerRef);
        booking.setStatus(BookingStatus.PENDING_PAYMENT);
        booking.setQuantity(quantity);
        booking.setExpiresAt(now.plus(properties.holdDuration()));
        return booking;
    }

    // ------------------------------------------------------------------ payment

    /**
     * First step of payment: validates the hold and records a durable {@link Payment} in the
     * INITIATED state, then returns a snapshot so the gateway can be charged <em>outside</em> any
     * transaction. Idempotent — a repeated call reuses the existing payment row and its key.
     */
    @Transactional
    public PaymentContext beginPayment(Long bookingId, String customerRef) {
        Instant now = clock.instant();
        Booking booking = getOwnedBooking(bookingId, customerRef);

        if (booking.getStatus() != BookingStatus.PENDING_PAYMENT) {
            throw new BusinessRuleException(
                    "Booking is not awaiting payment (status " + booking.getStatus() + ").");
        }
        if (booking.isExpired(now)) {
            throw new BusinessRuleException("The hold has expired. Please start a new booking.");
        }

        Payment payment = paymentRepository.findByBookingId(bookingId).orElseGet(() -> {
            Payment created = new Payment();
            created.setBooking(booking);
            created.setIdempotencyKey("booking-" + bookingId);
            created.setCustomerRef(booking.getCustomerRef());
            created.setAmount(booking.getTotalAmount());
            return created;
        });
        if (payment.getStatus() == PaymentStatus.SUCCEEDED) {
            throw new BusinessRuleException("This booking has already been paid.");
        }
        payment.setStatus(PaymentStatus.INITIATED);
        payment.setFailureReason(null);
        Payment saved = paymentRepository.save(payment);

        return new PaymentContext(saved.getId(), bookingId, booking.getCustomerRef(),
                booking.getTotalAmount(), saved.getIdempotencyKey());
    }

    /**
     * Final step of payment: records the gateway outcome and, on success, confirms the booking —
     * all in one transaction. If the charge succeeded but the hold no longer exists, the payment
     * is left INITIATED and an error is raised so reconciliation issues a refund.
     */
    @Transactional
    public PaymentResponse applyPaymentResult(Long paymentId, PaymentResult result) {
        Instant now = clock.instant();
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> ResourceNotFoundException.of("Payment", paymentId));
        Booking booking = payment.getBooking();

        // Idempotent: if already finalized, report the current state.
        if (payment.getStatus() == PaymentStatus.SUCCEEDED) {
            return paymentResponse(booking, true, "Payment already confirmed.");
        }
        if (payment.getStatus() == PaymentStatus.FAILED || payment.getStatus() == PaymentStatus.REFUNDED) {
            return paymentResponse(booking, false, "Payment was not successful.");
        }

        if (!result.success()) {
            payment.setStatus(PaymentStatus.FAILED);
            payment.setFailureReason(result.message());
            return paymentResponse(booking, false, "Payment failed: " + result.message());
        }

        if (booking.getStatus() != BookingStatus.PENDING_PAYMENT || booking.isExpired(now)) {
            // Charged, but the hold is gone. Leave INITIATED for the reconciler to refund.
            throw new BusinessRuleException(
                    "The hold expired during payment; the charge will be refunded shortly.");
        }

        confirmPaidBooking(booking, payment, result.reference(), now);
        return paymentResponse(booking, true, "Payment successful. Booking confirmed.");
    }

    private void confirmPaidBooking(Booking booking, Payment payment, String reference, Instant now) {
        payment.setStatus(PaymentStatus.SUCCEEDED);
        payment.setReference(reference);
        booking.setStatus(BookingStatus.CONFIRMED);
        booking.setConfirmedAt(now);
        booking.setPaymentRef(reference);
        for (BookingSeat seat : booking.getBookingSeats()) {
            if (seat.getStatus() == BookingSeatStatus.HELD) {
                seat.setStatus(BookingSeatStatus.BOOKED);
            }
        }
        maybeMarkSoldOut(booking.getEvent());
        evictAfterCommit(booking.getEvent().getId());
    }

    private PaymentResponse paymentResponse(Booking booking, boolean success, String message) {
        return new PaymentResponse(booking.getId(), booking.getStatus(), booking.getPaymentRef(), success, message);
    }

    // ------------------------------------------------------------------ reconciliation

    /** In-doubt payments (INITIATED and untouched for a while) that need reconciling. */
    @Transactional(readOnly = true)
    public List<PaymentSummary> findPaymentsToReconcile() {
        Instant threshold = clock.instant().minus(properties.reconcileAfter());
        return paymentRepository.findByStatusAndUpdatedAtBefore(PaymentStatus.INITIATED, threshold).stream()
                .map(p -> new PaymentSummary(p.getId(), p.getIdempotencyKey()))
                .toList();
    }

    /**
     * Resolves one in-doubt payment given what the gateway reports. Confirms the booking if the
     * charge succeeded and the hold still stands; signals a refund if the charge succeeded but the
     * seats are gone; marks the payment FAILED if no charge ever happened.
     */
    @Transactional
    public ReconcileOutcome reconcile(Long paymentId, java.util.Optional<PaymentResult> gatewayResult) {
        Instant now = clock.instant();
        Payment payment = paymentRepository.findById(paymentId).orElse(null);
        if (payment == null || payment.getStatus() != PaymentStatus.INITIATED) {
            return ReconcileOutcome.NONE;
        }

        if (gatewayResult.isEmpty() || !gatewayResult.get().success()) {
            payment.setStatus(PaymentStatus.FAILED);
            payment.setFailureReason("No successful charge found during reconciliation.");
            return ReconcileOutcome.NONE;
        }

        String reference = gatewayResult.get().reference();
        Booking booking = payment.getBooking();
        if (booking.getStatus() == BookingStatus.CONFIRMED) {
            payment.setStatus(PaymentStatus.SUCCEEDED);
            payment.setReference(reference);
            return ReconcileOutcome.NONE;
        }
        if (booking.getStatus() == BookingStatus.PENDING_PAYMENT && !booking.isExpired(now)) {
            confirmPaidBooking(booking, payment, reference, now);
            return ReconcileOutcome.NONE;
        }
        // Charged, but the hold is gone (expired/cancelled) — the job must refund.
        return ReconcileOutcome.refund(reference);
    }

    @Transactional
    public void markRefunded(Long paymentId, String reference) {
        paymentRepository.findById(paymentId).ifPresent(payment -> {
            payment.setStatus(PaymentStatus.REFUNDED);
            payment.setReference(reference);
            payment.setFailureReason("Refunded: the hold was no longer valid when payment settled.");
        });
    }

    // ------------------------------------------------------------------ cancel

    @Transactional
    public BookingResponse cancelBooking(Long bookingId, String customerRef) {
        Booking booking = getOwnedBooking(bookingId, customerRef);
        if (booking.getStatus() != BookingStatus.PENDING_PAYMENT) {
            throw new BusinessRuleException(
                    "Only a pending booking can be cancelled (status " + booking.getStatus() + ").");
        }
        booking.setStatus(BookingStatus.CANCELLED);
        releaseHeldSeats(booking);
        evictAfterCommit(booking.getEvent().getId());
        return BookingResponse.from(booking);
    }

    /**
     * Swaps the seats held by a pending booking. Seats no longer wanted are released and new ones
     * are acquired in the same transaction; seats kept in both sets are left untouched. The DB
     * unique index still guards every acquired seat, so a seat taken in the meantime yields a
     * conflict and nothing changes. The hold timer is reset.
     */
    @Transactional
    public BookingResponse changeSeats(Long bookingId, String customerRef, List<Long> requestedSeatIds) {
        Instant now = clock.instant();
        Booking booking = getOwnedBooking(bookingId, customerRef);

        if (booking.getStatus() != BookingStatus.PENDING_PAYMENT) {
            throw new BusinessRuleException(
                    "Only a pending booking's seats can be changed (status " + booking.getStatus() + ").");
        }
        if (booking.isExpired(now)) {
            throw new BusinessRuleException("The hold has expired. Please start a new booking.");
        }
        Event event = booking.getEvent();
        if (!event.getHall().isSeated()) {
            throw new BusinessRuleException("This booking is general admission; there are no seats to change.");
        }

        if (requestedSeatIds == null || requestedSeatIds.isEmpty()) {
            throw new BusinessRuleException("Provide at least one seat.");
        }
        Set<Long> requested = new LinkedHashSet<>(requestedSeatIds);
        if (requested.size() != requestedSeatIds.size()) {
            throw new BusinessRuleException("Duplicate seat ids in request.");
        }
        int max = properties.maxSeatsPerBooking();
        if (requested.size() > max) {
            throw new BusinessRuleException("You can hold at most " + max + " seats per booking.");
        }

        // Free expired holds so wanted seats become available; flush before any new inserts.
        releaseExpiredForEvent(event.getId(), now);
        bookingRepository.flush();

        // Seats currently held by this booking, and the ones being dropped (current - requested).
        Set<Long> currentSeatIds = new LinkedHashSet<>();
        for (BookingSeat bs : booking.getBookingSeats()) {
            if (bs.getStatus() == BookingSeatStatus.HELD) {
                currentSeatIds.add(bs.getSeat().getId());
                if (!requested.contains(bs.getSeat().getId())) {
                    bs.setStatus(BookingSeatStatus.RELEASED);
                }
            }
        }

        Set<Long> toAdd = new LinkedHashSet<>(requested);
        toAdd.removeAll(currentSeatIds);
        if (!toAdd.isEmpty()) {
            List<Seat> seats = seatRepository.findAllById(toAdd);
            if (seats.size() != toAdd.size()) {
                throw new ResourceNotFoundException("One or more requested seats do not exist.");
            }
            Long hallId = event.getHall().getId();
            for (Seat seat : seats) {
                if (!seat.getHall().getId().equals(hallId)) {
                    throw new BusinessRuleException(
                            "Seat " + seat.getLabel() + " does not belong to this event's hall.");
                }
            }
            List<BookingSeat> activeElsewhere =
                    bookingSeatRepository.findForSeats(event.getId(), toAdd, ACTIVE_SEAT_STATUSES).stream()
                            .filter(bs -> !bs.getBooking().getId().equals(bookingId))
                            .toList();
            if (!activeElsewhere.isEmpty()) {
                String taken = activeElsewhere.stream()
                        .map(bs -> bs.getSeat().getLabel())
                        .distinct()
                        .collect(Collectors.joining(", "));
                throw new ConflictException("These seats are already reserved or booked: " + taken + ".");
            }
            Map<SeatType, BigDecimal> priceByType = pricingByType(event);
            for (Seat seat : seats) {
                BigDecimal price = priceByType.get(seat.getSeatType());
                if (price == null) {
                    throw new BusinessRuleException("No price configured for seat type " + seat.getSeatType() + ".");
                }
                BookingSeat bookingSeat = new BookingSeat();
                bookingSeat.setEvent(event);
                bookingSeat.setSeat(seat);
                bookingSeat.setSeatType(seat.getSeatType());
                bookingSeat.setPrice(price);
                bookingSeat.setStatus(BookingSeatStatus.HELD);
                booking.addBookingSeat(bookingSeat);
            }
        }

        BigDecimal total = BigDecimal.ZERO;
        int quantity = 0;
        for (BookingSeat bs : booking.getBookingSeats()) {
            if (bs.getStatus() == BookingSeatStatus.HELD) {
                total = total.add(bs.getPrice());
                quantity++;
            }
        }
        booking.setTotalAmount(total);
        booking.setQuantity(quantity);
        booking.setExpiresAt(now.plus(properties.holdDuration()));

        try {
            bookingRepository.saveAndFlush(booking);
        } catch (DataIntegrityViolationException ex) {
            throw new ConflictException(
                    "One or more selected seats were just taken. Please choose different seats.");
        }
        evictAfterCommit(event.getId());
        return BookingResponse.from(booking);
    }

    // ------------------------------------------------------------------ expiry

    /** Releases every expired pending hold system-wide. Invoked by the scheduled sweeper. */
    @Transactional
    public int releaseExpired() {
        Instant now = clock.instant();
        List<Booking> expired =
                bookingRepository.findByStatusAndExpiresAtBefore(BookingStatus.PENDING_PAYMENT, now);
        expired.forEach(this::expire);
        return expired.size();
    }

    private void releaseExpiredForEvent(Long eventId, Instant now) {
        List<Booking> expired = bookingRepository.findByEventIdAndStatusAndExpiresAtBefore(
                eventId, BookingStatus.PENDING_PAYMENT, now);
        expired.forEach(this::expire);
    }

    private void expire(Booking booking) {
        booking.setStatus(BookingStatus.EXPIRED);
        releaseHeldSeats(booking);
    }

    private void releaseHeldSeats(Booking booking) {
        for (BookingSeat seat : booking.getBookingSeats()) {
            if (seat.getStatus() == BookingSeatStatus.HELD) {
                seat.setStatus(BookingSeatStatus.RELEASED);
            }
        }
    }

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public BookingResponse getBooking(Long bookingId, String customerRef) {
        return BookingResponse.from(getOwnedBooking(bookingId, customerRef));
    }

    @Cacheable(cacheNames = CacheNames.EVENT_SEAT_MAP, key = "#eventId")
    @Transactional(readOnly = true)
    public EventSeatMapResponse getSeatMap(Long eventId) {
        Instant now = clock.instant();
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> ResourceNotFoundException.of("Event", eventId));
        Hall hall = event.getHall();
        if (!hall.isSeated()) {
            throw new BusinessRuleException(
                    "This event is general admission; use the availability endpoint instead.");
        }

        Map<SeatType, BigDecimal> priceByType = pricingByType(event);
        List<Seat> seats = seatRepository.findByHallIdOrderByRowIndexAscSeatNumberAsc(hall.getId());
        Map<Long, SeatAvailabilityStatus> statusBySeat =
                resolveActiveSeatStatuses(eventId, now);

        List<SeatAvailabilityResponse> items = new ArrayList<>(seats.size());
        long available = 0;
        long reserved = 0;
        long booked = 0;
        for (Seat seat : seats) {
            SeatAvailabilityStatus status =
                    statusBySeat.getOrDefault(seat.getId(), SeatAvailabilityStatus.AVAILABLE);
            switch (status) {
                case AVAILABLE -> available++;
                case RESERVED -> reserved++;
                case BOOKED -> booked++;
            }
            items.add(new SeatAvailabilityResponse(
                    seat.getId(), seat.getLabel(), seat.getRowLabel(), seat.getRowIndex(),
                    seat.getSeatNumber(), seat.getSeatType(), priceByType.get(seat.getSeatType()), status));
        }

        return new EventSeatMapResponse(eventId, hall.getId(), hall.getName(), true,
                seats.size(), available, reserved, booked, items);
    }

    private Map<Long, SeatAvailabilityStatus> resolveActiveSeatStatuses(Long eventId, Instant now) {
        Map<Long, SeatAvailabilityStatus> statusBySeat = new java.util.HashMap<>();
        List<BookingSeat> active = bookingSeatRepository.findActiveForEvent(eventId, ACTIVE_SEAT_STATUSES);
        for (BookingSeat bs : active) {
            Long seatId = bs.getSeat().getId();
            if (bs.getStatus() == BookingSeatStatus.BOOKED) {
                statusBySeat.put(seatId, SeatAvailabilityStatus.BOOKED);
            } else if (!bs.getBooking().isExpired(now)) {
                // A booked seat always wins over a (theoretical) concurrent hold.
                statusBySeat.putIfAbsent(seatId, SeatAvailabilityStatus.RESERVED);
            }
        }
        return statusBySeat;
    }

    @Cacheable(cacheNames = CacheNames.EVENT_AVAILABILITY, key = "#eventId")
    @Transactional(readOnly = true)
    public EventAvailabilityResponse getAvailability(Long eventId) {
        Instant now = clock.instant();
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> ResourceNotFoundException.of("Event", eventId));
        if (event.getHall().isSeated()) {
            throw new BusinessRuleException(
                    "This event is seated; use the seat-map endpoint instead.");
        }
        long confirmed = bookingRepository.sumConfirmedQuantity(eventId);
        long reserved = bookingRepository.sumReservedQuantity(eventId, now);
        long available = Math.max(0, event.getMaxCapacity() - (confirmed + reserved));
        boolean soldOut = event.getMaxCapacity() - confirmed <= 0;
        return new EventAvailabilityResponse(eventId, false, event.getMaxCapacity(),
                confirmed, reserved, available, soldOut, generalAdmissionPrice(event));
    }

    // ------------------------------------------------------------------ helpers

    private Booking getBookingEntity(Long bookingId) {
        return bookingRepository.findById(bookingId)
                .orElseThrow(() -> ResourceNotFoundException.of("Booking", bookingId));
    }

    /**
     * Loads a booking only if it belongs to {@code customerRef}. A booking owned by someone else
     * is reported as not found so ids cannot be probed for existence.
     */
    private Booking getOwnedBooking(Long bookingId, String customerRef) {
        return bookingRepository.findById(bookingId)
                .filter(booking -> booking.getCustomerRef().equals(customerRef))
                .orElseThrow(() -> ResourceNotFoundException.of("Booking", bookingId));
    }

    private Map<SeatType, BigDecimal> pricingByType(Event event) {
        Map<SeatType, BigDecimal> map = new EnumMap<>(SeatType.class);
        for (EventPricing pricing : event.getPricing()) {
            if (pricing.getSeatType() != null) {
                map.put(pricing.getSeatType(), pricing.getPrice());
            }
        }
        return map;
    }

    private BigDecimal generalAdmissionPrice(Event event) {
        return event.getPricing().stream()
                .filter(p -> p.getSeatType() == null)
                .map(EventPricing::getPrice)
                .findFirst()
                .orElse(null);
    }

    private void maybeMarkSoldOut(Event event) {
        if (event.getStatus() != EventStatus.PUBLISHED) {
            return;
        }
        long confirmedUnits = event.getHall().isSeated()
                ? bookingSeatRepository.countByEventIdAndStatus(event.getId(), BookingSeatStatus.BOOKED)
                : bookingRepository.sumConfirmedQuantity(event.getId());
        if (confirmedUnits >= event.getMaxCapacity()) {
            event.setStatus(EventStatus.SOLD_OUT);
        }
    }

    /**
     * Evicts the cached availability for an event, deferred until the surrounding transaction
     * commits so a concurrent read can't repopulate the cache with pre-commit state.
     */
    private void evictAfterCommit(Long eventId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    evictAvailability(eventId);
                }
            });
        } else {
            evictAvailability(eventId);
        }
    }

    private void evictAvailability(Long eventId) {
        evict(CacheNames.EVENT_SEAT_MAP, eventId);
        evict(CacheNames.EVENT_AVAILABILITY, eventId);
    }

    private void evict(String cacheName, Long eventId) {
        try {
            Cache cache = cacheManager.getCache(cacheName);
            if (cache != null) {
                cache.evictIfPresent(eventId);
            }
        } catch (RuntimeException ex) {
            // Best-effort: a Redis outage must never break a booking. The short TTL heals staleness.
            log.warn("Cache eviction failed for {} event {}: {}", cacheName, eventId, ex.getMessage());
        }
    }
}
