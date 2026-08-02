package com.eventticketing.reservation.service;

import com.eventticketing.catalog.domain.Event;
import com.eventticketing.catalog.domain.EventPricing;
import com.eventticketing.catalog.domain.EventStatus;
import com.eventticketing.catalog.domain.Hall;
import com.eventticketing.catalog.domain.Seat;
import com.eventticketing.catalog.domain.Section;
import com.eventticketing.catalog.domain.SectionBookingMode;
import com.eventticketing.catalog.dto.LayoutObjectResponse;
import com.eventticketing.catalog.dto.PointItem;
import com.eventticketing.catalog.repository.EventRepository;
import com.eventticketing.catalog.repository.SeatRepository;
import com.eventticketing.catalog.repository.SectionRepository;
import com.eventticketing.catalog.service.SectionGeometry;
import com.eventticketing.common.exception.BusinessRuleException;
import com.eventticketing.common.exception.ConflictException;
import com.eventticketing.common.exception.ResourceNotFoundException;
import com.eventticketing.common.security.CustomerId;
import com.eventticketing.common.security.UnpaidHoldThrottle;
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
import com.eventticketing.reservation.domain.Ticket;
import com.eventticketing.reservation.dto.BookingResponse;
import com.eventticketing.reservation.dto.CreateBookingRequest;
import com.eventticketing.reservation.dto.EventAvailabilityResponse;
import com.eventticketing.reservation.dto.EventSeatMapResponse;
import com.eventticketing.reservation.dto.PaymentResponse;
import com.eventticketing.reservation.dto.SeatAvailabilityResponse;
import com.eventticketing.reservation.dto.SectionAvailabilityResponse;
import com.eventticketing.reservation.repository.BookingRepository;
import com.eventticketing.reservation.repository.BookingSeatRepository;
import com.eventticketing.reservation.repository.PaymentRepository;
import com.eventticketing.reservation.repository.TicketRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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

    /** Failed reconciliations before a payment is parked for a human. ~1h of backoff. */
    private static final int MAX_RECONCILE_ATTEMPTS = 6;
    private static final Duration RECONCILE_BASE_BACKOFF = Duration.ofSeconds(30);
    private static final Duration MAX_RECONCILE_BACKOFF = Duration.ofHours(1);
    /** Bounds one tick, so a backlog can never pull an unbounded set into memory. */
    private static final int RECONCILE_BATCH_SIZE = 200;

    private final BookingRepository bookingRepository;
    private final BookingSeatRepository bookingSeatRepository;
    private final PaymentRepository paymentRepository;
    private final EventRepository eventRepository;
    private final SeatRepository seatRepository;
    private final SectionRepository sectionRepository;
    private final TicketRepository ticketRepository;
    private final ReservationProperties properties;
    private final Clock clock;
    private final CacheManager cacheManager;
    private final ObjectMapper objectMapper;
    private final UnpaidHoldThrottle unpaidHoldThrottle;

    public ReservationService(BookingRepository bookingRepository,
                              BookingSeatRepository bookingSeatRepository,
                              PaymentRepository paymentRepository,
                              EventRepository eventRepository,
                              SeatRepository seatRepository,
                              SectionRepository sectionRepository,
                              TicketRepository ticketRepository,
                              ReservationProperties properties,
                              Clock clock,
                              CacheManager cacheManager,
                              ObjectMapper objectMapper,
                              UnpaidHoldThrottle unpaidHoldThrottle) {
        this.bookingRepository = bookingRepository;
        this.bookingSeatRepository = bookingSeatRepository;
        this.paymentRepository = paymentRepository;
        this.eventRepository = eventRepository;
        this.seatRepository = seatRepository;
        this.sectionRepository = sectionRepository;
        this.ticketRepository = ticketRepository;
        this.properties = properties;
        this.clock = clock;
        this.cacheManager = cacheManager;
        this.objectMapper = objectMapper;
        this.unpaidHoldThrottle = unpaidHoldThrottle;
    }

    // ------------------------------------------------------------------ booking

    @Transactional
    public BookingResponse createBooking(CreateBookingRequest request, CustomerId customer) {
        Instant now = clock.instant();
        requireNoActiveHold(customer, now);
    
        Event event = eventRepository.findById(request.eventId())
                .orElseThrow(() -> ResourceNotFoundException.of("Event", request.eventId()));

        if (event.getStatus() != EventStatus.PUBLISHED) {
            throw new BusinessRuleException(
                    "Event is not open for booking (status " + event.getStatus() + ").");
        }


        // Route by the request, not the hall: a hall may mix seated and general-admission sections.
        if (request.seatIds() != null && !request.seatIds().isEmpty()) {
            return bookSeats(event, request, customer, now);
        }
        if (request.sectionId() != null) {
            return bookGeneralAdmissionSection(event, request, customer, now);
        }
        // Legacy: a non-seated hall without sections books against the event's overall capacity.
        if (!event.getHall().isSeated()) {
            return bookGeneralAdmission(event, request, customer, now);
        }
        throw new BusinessRuleException(
                "Provide seatIds (seated) or a sectionId with quantity (general admission).");
    }

    /**
     * One live hold per customer. Without this, a single account can hold inventory across every
     * event at once — the payload cap only limits one request, not how many requests you make.
     *
     * <p>Deliberately scoped to the whole account rather than per event: an attacker spreading
     * holds across events does the same damage as concentrating them.
     */
    private void requireNoActiveHold(CustomerId customer, Instant now) {
        List<Booking> active = bookingRepository.findActiveHolds(customer.ref(), now);
        if (active.isEmpty()) {
            return;
        }
        Booking held = active.get(0);
        throw new ConflictException(
                "You already have seats on hold (booking " + held.getId()
                        + "). Pay for it or cancel it before starting another.");
    }

    private BookingResponse bookSeats(Event event, CreateBookingRequest request,
                                      CustomerId customer, Instant now) {
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

        List<Seat> seats = sortedById(seatRepository.findAllById(seatIds));
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

        Booking booking = newBooking(event, customer, seats.size(), now);
        BigDecimal total = BigDecimal.ZERO;
        for (Seat seat : seats) {
            BigDecimal price = seatPrice(event, seat);
            BookingSeat bookingSeat = new BookingSeat();
            bookingSeat.setEvent(event);
            bookingSeat.setSeat(seat);
            applySectionSnapshot(bookingSeat, seat.getSection());
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

    private BookingResponse bookGeneralAdmission(Event event, CreateBookingRequest request,
                                                 CustomerId customer, Instant now) {
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

        Booking booking = newBooking(locked, customer, quantity, now);
        booking.setTotalAmount(price.multiply(BigDecimal.valueOf(quantity)));
        bookingRepository.save(booking);
        evictAfterCommit(locked.getId());
        return BookingResponse.from(booking);
    }

    /**
     * Books tickets in a general-admission section, enforcing that section's own capacity
     * (independent of any seated sections in the same hall). Availability =
     * {@code capacity - confirmed - reserved} for that section.
     */
    private BookingResponse bookGeneralAdmissionSection(Event event, CreateBookingRequest request,
                                                        CustomerId customer, Instant now) {
        Integer quantity = request.quantity();
        if (quantity == null || quantity < 1) {
            throw new BusinessRuleException("A general-admission booking requires a quantity of at least 1.");
        }
        int max = properties.maxSeatsPerBooking();
        if (quantity > max) {
            throw new BusinessRuleException("You can book at most " + max + " tickets per booking.");
        }

        Section section = sectionRepository.findById(request.sectionId())
                .orElseThrow(() -> ResourceNotFoundException.of("Section", request.sectionId()));
        if (!section.getHall().getId().equals(event.getHall().getId())) {
            throw new BusinessRuleException("That section does not belong to this event's hall.");
        }
        if (section.getBookingMode() != SectionBookingMode.GENERAL_ADMISSION) {
            throw new BusinessRuleException(
                    "Section '" + section.getName() + "' is seated; pick individual seats instead.");
        }
        if (section.getCapacity() == null) {
            throw new BusinessRuleException("Section '" + section.getName() + "' has no configured capacity.");
        }

        // --- Outside the lock -------------------------------------------------------------
        // Everything that does not have to be consistent with the capacity check is done first.
        // Under a flash sale the section lock is the throughput ceiling — one sale at a time —
        // so every statement moved out of the critical section is throughput bought back.
        // Reclaiming expired holds touches other bookings, not this section's counters, and
        // pricing is read-only reference data; neither needs to be serialised.
        releaseExpiredForEvent(event.getId(), now);
        bookingRepository.flush();

        BigDecimal price = resolveSectionPrice(event, section);
        if (price == null) {
            throw new BusinessRuleException("No price configured for section '" + section.getName() + "'.");
        }

        // --- Critical section -------------------------------------------------------------
        // Capacity has no per-ticket identity, so no constraint can express "sum <= capacity".
        // The count and the insert must therefore be serialised against each other, and nothing
        // else belongs between them. Locking the section rather than the event lets other
        // sections of the same event keep selling in parallel.
        sectionRepository.findByIdForUpdate(section.getId())
                .orElseThrow(() -> ResourceNotFoundException.of("Section", section.getId()));

        long confirmed = bookingRepository.sumConfirmedQuantityBySection(section.getId());
        long reserved = bookingRepository.sumReservedQuantityBySection(section.getId(), now);
        long available = section.getCapacity() - (confirmed + reserved);
        if (quantity > available) {
            throw new ConflictException("Not enough capacity in '" + section.getName()
                    + "': only " + Math.max(available, 0) + " left.");
        }

        Booking booking = newBooking(event, customer, quantity, now);
        booking.setSection(section);
        booking.setTotalAmount(price.multiply(BigDecimal.valueOf(quantity)));
        bookingRepository.save(booking);
        evictAfterCommit(event.getId());
        return BookingResponse.from(booking);
    }

    /**
     * Orders seats by id so every transaction inserts its holds in the same sequence. Two requests
     * for overlapping seat sets would otherwise take index locks in opposite orders and deadlock;
     * a global ordering makes one of them simply wait, then lose cleanly on the unique index.
     */
    private List<Seat> sortedById(List<Seat> seats) {
        return seats.stream()
                .sorted(java.util.Comparator.comparing(Seat::getId))
                .toList();
    }

    private Booking newBooking(Event event, CustomerId customer, int quantity, Instant now) {
        Booking booking = new Booking();
        booking.setEvent(event);
        booking.setCustomerRef(customer.ref());
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
            created.setCustomerRef(booking.getCustomerRef());
            created.setAttempt(1);
            created.setIdempotencyKey(idempotencyKey(bookingId, 1));
            return created;
        });
        if (payment.getStatus() == PaymentStatus.SUCCEEDED) {
            throw new BusinessRuleException("This booking has already been paid.");
        }
        // An INITIATED payment is in doubt: the gateway may already hold a charge we never
        // recorded. Re-keying now would orphan it — reconciliation looks the charge up by the key
        // on this row, and the old one would be gone. Make the caller wait for that to settle.
        if (payment.getStatus() == PaymentStatus.INITIATED && payment.getId() != null) {
            throw new ConflictException(
                    "A payment for this booking is already being processed. Check back shortly.");
        }
        // A terminal failure means the gateway has stored an outcome against the current key and
        // would replay it. Advance to a fresh key so this really is a new charge.
        if (payment.getStatus() == PaymentStatus.FAILED) {
            payment.setAttempt(payment.getAttempt() + 1);
            payment.setIdempotencyKey(idempotencyKey(bookingId, payment.getAttempt()));
            payment.setReference(null);
        }
        // Always re-read the amount: changeSeats can alter the total while the hold is pending, and
        // charging one figure while recording another would leave the audit trail disagreeing with
        // the money — and a provider reject the key/amount mismatch outright.
        payment.setAmount(booking.getTotalAmount());
        payment.setStatus(PaymentStatus.INITIATED);
        payment.setFailureReason(null);
        Payment saved;
        try {
            saved = paymentRepository.saveAndFlush(payment);
        } catch (DataIntegrityViolationException ex) {
            // Two concurrent pay() calls both saw "no payment yet"; the unique booking_id index
            // let exactly one through. Report the loser as a conflict rather than a 500 — the
            // winner's charge is already in flight under the same idempotency key.
            throw new ConflictException("A payment for this booking is already being processed.");
        }

        return new PaymentContext(saved.getId(), bookingId, booking.getCustomerRef(),
                saved.getAmount(), saved.getIdempotencyKey());
    }

    /**
     * One key per attempt. The provider replays a stored response for a repeated key, so this is
     * the boundary between "retry the same charge safely" and "make a genuinely new charge".
     */
    private String idempotencyKey(Long bookingId, int attempt) {
        return "booking-" + bookingId + "-" + attempt;
    }

    /**
     * Records the provider's reference the moment a charge comes back, in its own transaction.
     *
     * <p>Called between the charge and {@link #applyPaymentResult} because that method deliberately
     * rolls back when the hold has expired — any reference written there would be discarded along
     * with it. Persisting separately means reconciliation can refund by reference directly instead
     * of depending on an idempotency-key lookup, which providers only honour for a limited window.
     */
    @Transactional
    public void recordChargeIssued(Long paymentId, String reference) {
        if (reference == null) {
            return;
        }
        paymentRepository.findById(paymentId).ifPresent(payment -> {
            if (payment.getStatus() == PaymentStatus.INITIATED && payment.getReference() == null) {
                payment.setReference(reference);
            }
        });
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
        generateTickets(booking);
        maybeMarkSoldOut(booking.getEvent());
        evictAfterCommit(booking.getEvent().getId());
        // The customer converted, so their unpaid-hold tally is wiped: paying must never leave
        // someone waiting out a throttle meant for squatters. Deferred to after commit so a
        // rolled-back confirmation cannot hand out a fresh budget.
        clearThrottleAfterCommit(booking.getCustomerRef());
    }

    private void clearThrottleAfterCommit(String customerRef) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    unpaidHoldThrottle.onPaymentConfirmed(customerRef);
                }
            });
        } else {
            unpaidHoldThrottle.onPaymentConfirmed(customerRef);
        }
    }

    /**
     * Generates the admission tickets for a just-confirmed booking: one per booked seat (seated) or
     * one per purchased quantity (general admission). Idempotent — a booking that already has
     * tickets is left untouched.
     */
    private void generateTickets(Booking booking) {
        if (!booking.getTickets().isEmpty()) {
            return;
        }
        Long eventId = booking.getEvent().getId();
        int seq = 1;
        List<BookingSeat> booked = booking.getBookingSeats().stream()
                .filter(bs -> bs.getStatus() == BookingSeatStatus.BOOKED)
                .toList();
        if (!booked.isEmpty()) {
            for (BookingSeat bs : booked) {
                Ticket ticket = new Ticket();
                ticket.setEventId(eventId);
                ticket.setSeatId(bs.getSeat().getId());
                ticket.setSeatLabel(bs.getSeat().getLabel());
                ticket.setSectionId(bs.getSectionId());
                ticket.setSectionName(bs.getSectionName());
                ticket.setTicketNumber(ticketNumber(booking.getId(), seq++));
                booking.addTicket(ticket);
            }
        } else {
            Section section = booking.getSection();
            for (int i = 0; i < booking.getQuantity(); i++) {
                Ticket ticket = new Ticket();
                ticket.setEventId(eventId);
                ticket.setSectionId(section != null ? section.getId() : null);
                ticket.setSectionName(section != null ? section.getName() : null);
                ticket.setTicketNumber(ticketNumber(booking.getId(), seq++));
                booking.addTicket(ticket);
            }
        }
    }

    private String ticketNumber(Long bookingId, int seq) {
        return "TKT-%d-%03d".formatted(bookingId, seq);
    }

    private PaymentResponse paymentResponse(Booking booking, boolean success, String message) {
        return new PaymentResponse(booking.getId(), booking.getStatus(), booking.getPaymentRef(), success, message);
    }

    // ------------------------------------------------------------------ reconciliation

    /** In-doubt payments (INITIATED and untouched for a while) that need reconciling. */
    @Transactional(readOnly = true)
    public List<PaymentSummary> findPaymentsToReconcile() {
        Instant now = clock.instant();
        // Backoff is applied by asking for rows whose last attempt is older than the *longest*
        // delay any row could be owed, then filtering precisely per attempt count. One query,
        // and a row is never retried sooner than its own backoff allows.
        List<Payment> candidates = paymentRepository.findReconciliationQueue(
                now.minus(properties.reconcileAfter()),
                now.minus(MAX_RECONCILE_BACKOFF),
                PageRequest.of(0, RECONCILE_BATCH_SIZE));

        return candidates.stream()
                .filter(p -> isDueForRetry(p, now))
                .map(p -> new PaymentSummary(p.getId(), p.getIdempotencyKey()))
                .toList();
    }

    /** Exponential backoff: 30s, 1m, 2m, 4m… capped, so transients recover without hammering. */
    private boolean isDueForRetry(Payment payment, Instant now) {
        if (payment.getLastReconcileAt() == null) {
            return true;
        }
        long seconds = Math.min(
                RECONCILE_BASE_BACKOFF.getSeconds() * (1L << Math.min(payment.getReconcileAttempts(), 12)),
                MAX_RECONCILE_BACKOFF.getSeconds());
        return payment.getLastReconcileAt().plusSeconds(seconds).isBefore(now);
    }

    /**
     * Records that reconciliation could not resolve a payment, and dead-letters it once it has
     * failed too often.
     *
     * <p>Runs in its own transaction because the attempt that failed has already rolled back —
     * which is precisely why the row's {@code updated_at} never moved and it was retried forever.
     * Parking it in {@link PaymentStatus#NEEDS_REVIEW} removes it from the queue so a broken
     * record stops delaying the healthy in-doubt payments behind it.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordReconcileFailure(Long paymentId, String error) {
        paymentRepository.findById(paymentId).ifPresent(payment -> {
            boolean stillOurs = payment.getStatus() == PaymentStatus.INITIATED
                    || payment.getStatus() == PaymentStatus.REFUND_PENDING;
            if (!stillOurs) {
                return; // resolved by another run in the meantime
            }
            payment.setReconcileAttempts(payment.getReconcileAttempts() + 1);
            payment.setLastReconcileAt(clock.instant());
            payment.setLastReconcileError(truncate(error));
            if (payment.getReconcileAttempts() >= MAX_RECONCILE_ATTEMPTS) {
                payment.setStatus(PaymentStatus.NEEDS_REVIEW);
                log.error("Payment {} dead-lettered after {} failed reconciliations: {}",
                        paymentId, payment.getReconcileAttempts(), payment.getLastReconcileError());
            }
        });
    }

    private String truncate(String value) {
        if (value == null) {
            return "Unknown error";
        }
        return value.length() <= 500 ? value : value.substring(0, 497) + "...";
    }

    // ------------------------------------------------------- dead letter operations

    /** How many payments are parked for an operator. Alert on any non-zero value. */
    @Transactional(readOnly = true)
    public long countPaymentsNeedingReview() {
        return paymentRepository.countByStatus(PaymentStatus.NEEDS_REVIEW);
    }

    @Transactional(readOnly = true)
    public List<PaymentReviewItem> listPaymentsNeedingReview() {
        return paymentRepository.findByStatusOrderByUpdatedAtDesc(PaymentStatus.NEEDS_REVIEW).stream()
                .map(PaymentReviewItem::from)
                .toList();
    }

    /**
     * Returns a dead-lettered payment to the reconciliation queue — used after an operator has
     * fixed the underlying cause (credentials, provider outage, a data problem).
     *
     * <p>The attempt counter is cleared so the row gets a full budget again; leaving it would send
     * the payment straight back to {@code NEEDS_REVIEW} on the first hiccup.
     */
    @Transactional
    public void requeueForReconciliation(Long paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> ResourceNotFoundException.of("Payment", paymentId));
        if (payment.getStatus() != PaymentStatus.NEEDS_REVIEW) {
            throw new BusinessRuleException(
                    "Only a payment awaiting review can be requeued (status " + payment.getStatus() + ").");
        }
        payment.setStatus(PaymentStatus.INITIATED);
        payment.setReconcileAttempts(0);
        payment.setLastReconcileAt(null);
        payment.setLastReconcileError(null);
        log.info("Payment {} requeued for reconciliation by an operator.", paymentId);
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
        if (payment == null) {
            return ReconcileOutcome.NONE;
        }
        // Resume a refund that was already decided and claimed but never confirmed — the worker
        // that owned it died between calling the provider and recording the result. Re-issuing is
        // safe: the refund carries the same reference, and the provider is idempotent on it.
        if (payment.getStatus() == PaymentStatus.REFUND_PENDING) {
            return ReconcileOutcome.refund(payment.getReference());
        }
        if (payment.getStatus() != PaymentStatus.INITIATED) {
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
        // Charged, but the hold is gone (expired/cancelled) — a refund is owed. Claim it inside
        // this transaction before returning the instruction, so no other replica and no later tick
        // can decide the same refund and issue it twice.
        payment.setStatus(PaymentStatus.REFUND_PENDING);
        payment.setReference(reference);
        return ReconcileOutcome.refund(reference);
    }

    @Transactional
    public void markRefunded(Long paymentId, String reference) {
        paymentRepository.findById(paymentId).ifPresent(payment -> {
            // Only the claim can be completed. Anything else means another worker already
            // finished this refund, so recording it again would be a lie about a second refund.
            if (payment.getStatus() != PaymentStatus.REFUND_PENDING) {
                return;
            }
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
            List<Seat> seats = sortedById(seatRepository.findAllById(toAdd));
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
            for (Seat seat : seats) {
                BigDecimal price = seatPrice(event, seat);
                BookingSeat bookingSeat = new BookingSeat();
                bookingSeat.setEvent(event);
                bookingSeat.setSeat(seat);
                applySectionSnapshot(bookingSeat, seat.getSection());
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
    /** How many expired holds one sweeper transaction will release. */
    private static final int SWEEP_BATCH_SIZE = 200;

    /**
     * Releases one bounded batch of expired holds. Bounded on purpose: an expiry wave after a
     * flash sale could otherwise pull every stale booking into a single transaction, holding row
     * locks across thousands of updates. The caller loops until a batch comes back short.
     *
     * @return the number released; a full batch means more remain
     */
    @Transactional
    public int releaseExpired() {
        Instant now = clock.instant();
        List<Booking> expired = bookingRepository.findByStatusAndExpiresAtBeforeOrderByExpiresAtAsc(
                BookingStatus.PENDING_PAYMENT, now, PageRequest.of(0, SWEEP_BATCH_SIZE));
        expired.forEach(this::expire);
        return expired.size();
    }

    /** True when a batch was full, i.e. {@link #releaseExpired()} should be called again. */
    public boolean isFullSweepBatch(int released) {
        return released >= SWEEP_BATCH_SIZE;
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

    @Transactional(readOnly = true)
    public List<BookingResponse> listBookings(String customerRef) {
        return bookingRepository.findByCustomerRefOrderByCreatedAtDescIdDesc(customerRef).stream()
                .map(BookingResponse::from)
                .toList();
    }

    // sync = true collapses a burst of concurrent misses for the same event into one
    // loader call per instance; without it every waiting request runs the query itself.
    @Cacheable(cacheNames = CacheNames.EVENT_SEAT_MAP, key = "#eventId", sync = true)
    @Transactional(readOnly = true)
    public EventSeatMapResponse getSeatMap(Long eventId) {
        Instant now = clock.instant();
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> ResourceNotFoundException.of("Event", eventId));
        Hall hall = event.getHall();

        List<Seat> seats = seatRepository.findByHallIdOrderByRowIndexAscSeatNumberAsc(hall.getId());
        Map<Long, SeatAvailabilityStatus> statusBySeat = resolveActiveSeatStatuses(eventId, now);

        List<SeatAvailabilityResponse> items = new ArrayList<>(seats.size());
        // Per-section seated tallies, keyed by section id, for deriving section availability.
        Map<Long, long[]> seatedTally = new java.util.HashMap<>(); // [available, reserved, booked]
        long available = 0;
        long reserved = 0;
        long booked = 0;
        for (Seat seat : seats) {
            SeatAvailabilityStatus status =
                    statusBySeat.getOrDefault(seat.getId(), SeatAvailabilityStatus.AVAILABLE);
            Long sectionId = seat.getSection() != null ? seat.getSection().getId() : null;
            long[] tally = sectionId == null ? null
                    : seatedTally.computeIfAbsent(sectionId, k -> new long[3]);
            switch (status) {
                case AVAILABLE -> { available++; if (tally != null) tally[0]++; }
                case RESERVED -> { reserved++; if (tally != null) tally[1]++; }
                case BOOKED -> { booked++; if (tally != null) tally[2]++; }
            }
            items.add(new SeatAvailabilityResponse(
                    seat.getId(), seat.getLabel(), seat.getRowLabel(), seat.getRowIndex(),
                    seat.getSeatNumber(), seat.getLayoutX(), seat.getLayoutY(),
                    seat.getRotationDegrees(), seat.getLayoutWidth(), seat.getLayoutHeight(),
                    seat.getSectionName(), sectionId, seatPriceOrNull(event, seat), status));
        }

        List<LayoutObjectResponse> layoutObjects = hall.getLayoutObjects().stream()
                .map(LayoutObjectResponse::from)
                .toList();

        List<SectionAvailabilityResponse> sections = hall.getSections().stream()
                .map(section -> sectionAvailability(event, section, seatedTally, now))
                .toList();

        return new EventSeatMapResponse(eventId, hall.getId(), hall.getName(), hall.isSeated(),
                hall.getLayoutWidth(), hall.getLayoutHeight(), seats.size(), available, reserved, booked,
                items, layoutObjects, sections);
    }

    private SectionAvailabilityResponse sectionAvailability(Event event, Section section,
                                                            Map<Long, long[]> seatedTally, Instant now) {
        BigDecimal price = resolveSectionPrice(event, section);
        List<PointItem> points = SectionGeometry.fromJson(objectMapper, section.getPoints());
        if (section.getBookingMode() == SectionBookingMode.GENERAL_ADMISSION) {
            int capacity = section.getCapacity() != null ? section.getCapacity() : 0;
            long confirmed = bookingRepository.sumConfirmedQuantityBySection(section.getId());
            long held = bookingRepository.sumReservedQuantityBySection(section.getId(), now);
            long free = Math.max(0, capacity - (confirmed + held));
            return new SectionAvailabilityResponse(section.getId(), section.getName(),
                    section.getBookingMode(), price, section.getCurrency(), capacity, free, held,
                    confirmed, section.getShapeKind(), points, section.getColor());
        }
        long[] tally = seatedTally.getOrDefault(section.getId(), new long[3]);
        int capacity = (int) (tally[0] + tally[1] + tally[2]);
        return new SectionAvailabilityResponse(section.getId(), section.getName(),
                section.getBookingMode(), price, section.getCurrency(), capacity, tally[0], tally[1],
                tally[2], section.getShapeKind(), points, section.getColor());
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

    // sync = true collapses a burst of concurrent misses for the same event into one
    // loader call per instance; without it every waiting request runs the query itself.
    @Cacheable(cacheNames = CacheNames.EVENT_AVAILABILITY, key = "#eventId", sync = true)
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

    /**
     * Resolves the price for a seat from its section. Every bookable seat must belong to one.
     */
    private BigDecimal seatPrice(Event event, Seat seat) {
        if (seat.getSection() == null) {
            throw new BusinessRuleException(
                    "Seat '" + seat.getLabel() + "' is not assigned to a section.");
        }
        BigDecimal sectionPrice = resolveSectionPrice(event, seat.getSection());
        if (sectionPrice != null) {
            return sectionPrice;
        }
        throw new BusinessRuleException(
                "No price configured for section '" + seat.getSection().getName() + "'.");
    }

    /** Non-throwing price resolution for reads (browsing an unpriced event must not fail). */
    private BigDecimal seatPriceOrNull(Event event, Seat seat) {
        return resolveSectionPrice(event, seat.getSection());
    }

    /**
     * The event's price for a section. Prices belong to the event, never to the hall, so an event
     * that has no price line for a section simply has no price for it (publishing is what enforces
     * that every section is priced).
     */
    private BigDecimal resolveSectionPrice(Event event, Section section) {
        if (section == null) {
            return null;
        }
        for (EventPricing p : event.getPricing()) {
            if (p.getSection() != null && p.getSection().getId().equals(section.getId())) {
                return p.getPrice();
            }
        }
        return null;
    }

    private void applySectionSnapshot(BookingSeat bookingSeat, Section section) {
        if (section == null) {
            return;
        }
        bookingSeat.setSectionId(section.getId());
        bookingSeat.setSectionName(section.getName());
        bookingSeat.setCurrency(section.getCurrency());
    }

    private BigDecimal generalAdmissionPrice(Event event) {
        return event.getHall().getSections().stream()
                .filter(section -> section.getBookingMode() == SectionBookingMode.GENERAL_ADMISSION)
                .map(section -> resolveSectionPrice(event, section))
                .filter(price -> price != null)
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
