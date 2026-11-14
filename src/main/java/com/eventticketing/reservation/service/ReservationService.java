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
import com.eventticketing.common.exception.BusinessRuleException;
import com.eventticketing.common.exception.ConflictException;
import com.eventticketing.common.exception.ResourceNotFoundException;
import com.eventticketing.common.security.CustomerId;
import com.eventticketing.payment.PaymentResult;
import com.eventticketing.reservation.config.ReservationProperties;
import com.eventticketing.reservation.domain.Booking;
import com.eventticketing.reservation.domain.BookingGaLine;
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
import com.eventticketing.reservation.dto.GeneralAdmissionSelection;
import com.eventticketing.reservation.dto.PaymentResponse;
import com.eventticketing.reservation.dto.SeatAvailabilityResponse;
import com.eventticketing.reservation.dto.SectionAvailabilityResponse;
import com.eventticketing.reservation.repository.BookingRepository;
import com.eventticketing.reservation.repository.BookingSeatRepository;
import com.eventticketing.reservation.repository.PaymentRepository;
import com.eventticketing.reservation.repository.TicketRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
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
    private final TicketRepository ticketRepository;
    private final ReservationProperties properties;
    private final Clock clock;

    public ReservationService(BookingRepository bookingRepository,
                              BookingSeatRepository bookingSeatRepository,
                              PaymentRepository paymentRepository,
                              EventRepository eventRepository,
                              SeatRepository seatRepository,
                              TicketRepository ticketRepository,
                              ReservationProperties properties,
                              Clock clock) {
        this.bookingRepository = bookingRepository;
        this.bookingSeatRepository = bookingSeatRepository;
        this.paymentRepository = paymentRepository;
        this.eventRepository = eventRepository;
        this.seatRepository = seatRepository;
        this.ticketRepository = ticketRepository;
        this.properties = properties;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ booking

    /**
     * Holds seats, general-admission tickets, or both in one order. Every item is reserved in this
     * transaction, or none is.
     *
     * <p>The event row lock serialises every capacity decision for the event. Seats are also
     * guarded by the database unique index, but GA tickets have no identity, so their section
     * counts are only correct because they run under this lock with READ_COMMITTED (a REPEATABLE
     * READ snapshot taken before the lock could miss a competing booking that just committed).
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public BookingResponse createBooking(CreateBookingRequest request, CustomerId customer) {
        Event event = eventRepository.findByIdForUpdate(request.eventId())
                .orElseThrow(() -> ResourceNotFoundException.of("Event", request.eventId()));
        Instant now = clock.instant();
        requireNoActiveHold(customer, now);
        requireOpenForBooking(event);
        Selection selection = normalizeSelection(request.seatIds(), request.generalAdmission());

        // Free expired holds so their seats and GA tickets become available. Flush the releases
        // now: Hibernate orders INSERTs before UPDATEs within one flush, so the release
        // (UPDATE -> active_lock NULL) must reach the DB before the new hold's INSERT.
        releaseExpiredForEvent(event.getId(), now);
        bookingRepository.flush();

        List<Seat> seats = resolveSeats(event, selection.seatIds());
        requireSeatsFree(event, selection.seatIds(), null);
        Map<Long, Section> gaSections = resolveGaSections(event, selection.gaQuantities().keySet());
        selection.gaQuantities().forEach((sectionId, quantity) ->
                requireGaCapacity(event, gaSections.get(sectionId), quantity, 0, now));

        Booking booking = newBooking(event, customer, selection.total(), now);
        for (Seat seat : seats) {
            booking.addBookingSeat(newSeatLine(event, seat));
        }
        selection.gaQuantities().forEach((sectionId, quantity) ->
                booking.addGaLine(newGaLine(event, gaSections.get(sectionId), quantity)));
        recalculateTotals(booking);

        try {
            bookingRepository.saveAndFlush(booking);
        } catch (DataIntegrityViolationException ex) {
            // Lost the race on the active-seat unique index.
            throw new ConflictException(
                    "One or more selected seats were just taken. Please choose different seats.");
        }
        return BookingResponse.from(booking);
    }

    /** A syntactically valid selection: distinct seat ids and one quantity per GA section. */
    private record Selection(Set<Long> seatIds, Map<Long, Integer> gaQuantities) {
        int total() {
            return seatIds.size() + gaQuantities.values().stream().mapToInt(Integer::intValue).sum();
        }
    }

    private Selection normalizeSelection(List<Long> seatIds, List<GeneralAdmissionSelection> generalAdmission) {
        List<Long> requestedSeats = seatIds == null ? List.of() : seatIds;
        Set<Long> seats = new LinkedHashSet<>(requestedSeats);
        if (seats.contains(null) || seats.size() != requestedSeats.size()) {
            throw new BusinessRuleException("Seat ids must be non-null and unique.");
        }

        Map<Long, Integer> gaQuantities = new LinkedHashMap<>();
        long total = seats.size();
        for (GeneralAdmissionSelection item : generalAdmission == null
                ? List.<GeneralAdmissionSelection>of() : generalAdmission) {
            if (item == null || item.sectionId() == null) {
                throw new BusinessRuleException("Each general-admission selection needs a sectionId.");
            }
            if (item.quantity() == null || item.quantity() < 1) {
                throw new BusinessRuleException("General-admission quantity must be at least 1.");
            }
            if (gaQuantities.put(item.sectionId(), item.quantity()) != null) {
                throw new BusinessRuleException("Each general-admission section may appear only once.");
            }
            total += item.quantity();
        }

        int max = maxTicketsPerBooking();
        if (total < 1 || total > max) {
            throw new BusinessRuleException("Select between 1 and " + max
                    + " tickets in total (seats plus general admission).");
        }
        return new Selection(seats, gaQuantities);
    }

    private int maxTicketsPerBooking() {
        return Math.min(CreateBookingRequest.MAX_SEATS, properties.maxSeatsPerBooking());
    }

    private void requireOpenForBooking(Event event) {
        if (event.getStatus() != EventStatus.PUBLISHED) {
            throw new BusinessRuleException(
                    "Event is not open for booking (status " + event.getStatus() + ").");
        }
    }

    /** Loads the seats, sorted by id, and checks each is a bookable seat of this event's hall. */
    private List<Seat> resolveSeats(Event event, Set<Long> seatIds) {
        if (seatIds.isEmpty()) {
            return List.of();
        }
        List<Seat> seats = sortedById(seatRepository.findAllById(seatIds));
        if (seats.size() != seatIds.size()) {
            throw new ResourceNotFoundException("One or more requested seats do not exist.");
        }
        for (Seat seat : seats) {
            if (!seat.getHall().getId().equals(event.getHall().getId())) {
                throw new BusinessRuleException(
                        "Seat " + seat.getLabel() + " does not belong to this event's hall.");
            }
            if (seat.getSection() == null || seat.getSection().getBookingMode() != SectionBookingMode.SEATED) {
                throw new BusinessRuleException("Seat " + seat.getLabel() + " must belong to a seated section.");
            }
        }
        return seats;
    }

    /**
     * Pre-check for a friendly error; the DB unique index is the real guard against races.
     * Seats already held by {@code ownBookingId} (when editing) do not count as taken.
     */
    private void requireSeatsFree(Event event, Set<Long> seatIds, Long ownBookingId) {
        if (seatIds.isEmpty()) {
            return;
        }
        String taken = bookingSeatRepository.findForSeats(event.getId(), seatIds, ACTIVE_SEAT_STATUSES).stream()
                .filter(bs -> ownBookingId == null || !ownBookingId.equals(bs.getBooking().getId()))
                .map(bs -> bs.getSeat().getLabel())
                .distinct()
                .collect(Collectors.joining(", "));
        if (!taken.isEmpty()) {
            throw new ConflictException("These seats are already reserved or booked: " + taken + ".");
        }
    }

    /** Resolves GA sections against the event's own hall, so a foreign section id is rejected. */
    private Map<Long, Section> resolveGaSections(Event event, Set<Long> sectionIds) {
        Map<Long, Section> hallSections = event.getHall().getSections().stream()
                .collect(Collectors.toMap(Section::getId, Function.identity()));
        Map<Long, Section> resolved = new LinkedHashMap<>();
        for (Long sectionId : sectionIds) {
            Section section = hallSections.get(sectionId);
            if (section == null) {
                throw new BusinessRuleException(
                        "Section " + sectionId + " does not belong to this event's hall.");
            }
            if (section.getBookingMode() != SectionBookingMode.GENERAL_ADMISSION) {
                throw new BusinessRuleException(
                        "Section '" + section.getName() + "' is seated; select individual seats instead.");
            }
            if (section.getCapacity() == null || section.getCapacity() < 1) {
                throw new BusinessRuleException(
                        "Section '" + section.getName() + "' has no configured capacity.");
            }
            resolved.put(sectionId, section);
        }
        return resolved;
    }

    /**
     * Checks that {@code requested} tickets fit in this event's section. {@code heldByThisBooking}
     * is what the booking being edited already holds there; it is part of the occupied count, so
     * it is given back before comparing.
     */
    private void requireGaCapacity(Event event, Section section, int requested, int heldByThisBooking,
                                   Instant now) {
        long occupied = bookingRepository.sumConfirmedQuantityBySection(event.getId(), section.getId())
                + bookingRepository.sumReservedQuantityBySection(event.getId(), section.getId(), now)
                - heldByThisBooking;
        long available = section.getCapacity() - occupied;
        if (requested > available) {
            throw new ConflictException("Not enough capacity in '" + section.getName()
                    + "': only " + Math.max(available, 0) + " left.");
        }
    }

    private BookingSeat newSeatLine(Event event, Seat seat) {
        BookingSeat line = new BookingSeat();
        line.setEvent(event);
        line.setSeat(seat);
        applySectionSnapshot(line, seat.getSection());
        line.setPrice(seatPrice(event, seat));
        line.setServiceFeePerTicket(serviceFee(seat.getSection()));
        line.setStatus(BookingSeatStatus.HELD);
        return line;
    }

    private BookingGaLine newGaLine(Event event, Section section, int quantity) {
        BigDecimal price = resolveSectionPrice(event, section);
        if (price == null) {
            throw new BusinessRuleException("No price configured for section '" + section.getName() + "'.");
        }
        BookingGaLine line = new BookingGaLine();
        line.setEvent(event);
        line.setSection(section);
        line.setSectionName(section.getName());
        line.setUnitPrice(price);
        line.setServiceFeePerTicket(serviceFee(section));
        line.setQuantity(quantity);
        return line;
    }

    private BigDecimal serviceFee(Section section) {
        var configured = section == null ? null : section.getServiceFeePerTicket();
        return configured == null ? BigDecimal.ONE : configured;
    }

    /** Derives quantity and total from the held seats and GA lines. */
    private void recalculateTotals(Booking booking) {
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal fees = BigDecimal.ZERO;
        int quantity = 0;
        for (BookingSeat seat : booking.heldSeats()) {
            total = total.add(seat.getPrice());
            fees = fees.add(seat.getServiceFeePerTicket());
            quantity++;
        }
        for (BookingGaLine line : booking.getGaLines()) {
            total = total.add(line.lineTotal());
            fees = fees.add(line.getServiceFeePerTicket().multiply(BigDecimal.valueOf(line.getQuantity())));
            quantity += line.getQuantity();
        }
        booking.setQuantity(quantity);
        booking.setServiceFee(fees);
        booking.setTotalAmount(total.add(fees));
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
        if (!active.isEmpty()) {
            throw new ConflictException("You already have seats on hold (booking " + active.get(0).getId()
                    + "). Pay for it or cancel it before starting another.");
        }
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
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PaymentContext beginPayment(Long bookingId, String customerRef) {
        Booking booking = getOwnedBookingForUpdate(bookingId, customerRef);
        Instant now = clock.instant();

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
        if (payment.getId() != null && payment.getStatus() != PaymentStatus.FAILED) {
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
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PaymentResponse applyPaymentResult(Long paymentId, PaymentResult result) {
        paymentRepository.findEventId(paymentId).ifPresent(eventId ->
                eventRepository.findByIdForUpdate(eventId));
        Instant now = clock.instant();
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> ResourceNotFoundException.of("Payment", paymentId));
        Booking booking = bookingRepository.findByIdForUpdate(payment.getBooking().getId())
                .orElseThrow(() -> ResourceNotFoundException.of("Booking", payment.getBooking().getId()));

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
        if (payment.getAmount().compareTo(booking.getTotalAmount()) != 0) {
            throw new ConflictException("The charged amount does not match the booking total; review is required.");
        }
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
    }

    /**
     * Generates the admission tickets for a just-confirmed booking: one per booked seat plus
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
        for (BookingGaLine line : booking.getGaLines()) {
            for (int i = 0; i < line.getQuantity(); i++) {
                Ticket ticket = new Ticket();
                ticket.setEventId(eventId);
                ticket.setSectionId(line.getSection() == null ? null : line.getSection().getId());
                ticket.setSectionName(line.getSectionName());
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
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ReconcileOutcome reconcile(Long paymentId, java.util.Optional<PaymentResult> gatewayResult) {
        paymentRepository.findEventId(paymentId).ifPresent(eventId ->
                eventRepository.findByIdForUpdate(eventId));
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
        Booking booking = bookingRepository.findByIdForUpdate(payment.getBooking().getId())
                .orElseThrow(() -> ResourceNotFoundException.of("Booking", payment.getBooking().getId()));
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

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public BookingResponse cancelBooking(Long bookingId, String customerRef) {
        Booking booking = getOwnedBookingForUpdate(bookingId, customerRef);
        if (booking.getStatus() != BookingStatus.PENDING_PAYMENT) {
            throw new BusinessRuleException(
                    "Only a pending booking can be cancelled (status " + booking.getStatus() + ").");
        }
        booking.setStatus(BookingStatus.CANCELLED);
        releaseHeldSeats(booking);
        return BookingResponse.from(booking);
    }

    /**
     * Replaces the whole selection of a pending booking: seats and GA tickets can be added,
     * removed, or have their quantity changed, and the result must satisfy the same rules as a new
     * booking. Unchanged seats and GA lines keep the price they were held at; only newly added
     * items are priced now. The original hold deadline is preserved, so repeated edits cannot
     * keep inventory held forever.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public BookingResponse changeItems(Long bookingId, String customerRef, List<Long> seatIds,
                                       List<GeneralAdmissionSelection> generalAdmission) {
        Booking booking = getOwnedBookingForUpdate(bookingId, customerRef);
        return replaceItems(booking, seatIds, generalAdmission);
    }

    /** Replaces only the seats of a pending booking, keeping its GA tickets as they are. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public BookingResponse changeSeats(Long bookingId, String customerRef, List<Long> seatIds) {
        Booking booking = getOwnedBookingForUpdate(bookingId, customerRef);
        List<GeneralAdmissionSelection> keep = booking.getGaLines().stream()
                .filter(line -> line.getSection() != null)
                .map(line -> new GeneralAdmissionSelection(line.getSection().getId(), line.getQuantity()))
                .toList();
        return replaceItems(booking, seatIds, keep);
    }

    private BookingResponse replaceItems(Booking booking, List<Long> seatIds,
                                         List<GeneralAdmissionSelection> generalAdmission) {
        Instant now = clock.instant();
        if (booking.getStatus() != BookingStatus.PENDING_PAYMENT) {
            throw new BusinessRuleException(
                    "Only a pending booking can be changed (status " + booking.getStatus() + ").");
        }
        if (booking.isExpired(now)) {
            throw new BusinessRuleException("The hold has expired. Please start a new booking.");
        }
        // The charged amount is fixed when payment begins; the order must not change under it.
        paymentRepository.findByBookingId(booking.getId()).ifPresent(payment -> {
            if (payment.getStatus() != PaymentStatus.FAILED) {
                throw new ConflictException("The booking cannot change while a payment is processing or unresolved.");
            }
        });
        Event event = booking.getEvent();
        requireOpenForBooking(event);
        Selection selection = normalizeSelection(seatIds, generalAdmission);

        releaseExpiredForEvent(event.getId(), now);
        bookingRepository.flush();

        // Validate everything before touching the booking.
        Map<Long, BookingSeat> heldBySeat = new LinkedHashMap<>();
        booking.heldSeats().forEach(bs -> heldBySeat.put(bs.getSeat().getId(), bs));
        Set<Long> seatsToAdd = new LinkedHashSet<>(selection.seatIds());
        seatsToAdd.removeAll(heldBySeat.keySet());
        List<Seat> addedSeats = resolveSeats(event, seatsToAdd);
        requireSeatsFree(event, seatsToAdd, booking.getId());

        Map<Long, BookingGaLine> linesBySection = new LinkedHashMap<>();
        booking.getGaLines().stream()
                .filter(line -> line.getSection() != null)
                .forEach(line -> linesBySection.put(line.getSection().getId(), line));
        Map<Long, Section> gaSections = resolveGaSections(event, selection.gaQuantities().keySet());
        selection.gaQuantities().forEach((sectionId, quantity) -> {
            BookingGaLine current = linesBySection.get(sectionId);
            int held = current == null ? 0 : current.getQuantity();
            if (quantity > held) {
                requireGaCapacity(event, gaSections.get(sectionId), quantity, held, now);
            }
        });

        // Apply: release dropped seats, hold new ones, and reshape the GA lines.
        heldBySeat.forEach((seatId, line) -> {
            if (!selection.seatIds().contains(seatId)) {
                line.setStatus(BookingSeatStatus.RELEASED);
            }
        });
        for (Seat seat : addedSeats) {
            booking.addBookingSeat(newSeatLine(event, seat));
        }
        booking.getGaLines().removeIf(line -> line.getSection() == null
                || !selection.gaQuantities().containsKey(line.getSection().getId()));
        selection.gaQuantities().forEach((sectionId, quantity) -> {
            BookingGaLine current = linesBySection.get(sectionId);
            if (current != null) {
                current.setQuantity(quantity);
            } else {
                booking.addGaLine(newGaLine(event, gaSections.get(sectionId), quantity));
            }
        });
        recalculateTotals(booking);

        try {
            bookingRepository.saveAndFlush(booking);
        } catch (DataIntegrityViolationException ex) {
            throw new ConflictException(
                    "One or more selected seats were just taken. Please choose different seats.");
        }
        return BookingResponse.from(booking);
    }

    // ------------------------------------------------------------------ expiry

    // There is no background sweeper. A lapsed hold is marked EXPIRED lazily: by the next booking
    // or edit on its event (releaseExpiredForEvent), or when its owner reads it (expireIfLapsed).
    // Availability never depends on that bookkeeping, because every capacity and seat-status
    // check compares expires_at with now.

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

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public BookingResponse getBooking(Long bookingId, String customerRef) {
        Booking booking = getOwnedBooking(bookingId, customerRef);
        return BookingResponse.from(expireIfLapsed(booking, clock.instant()));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<BookingResponse> listBookings(String customerRef) {
        Instant now = clock.instant();
        return bookingRepository.findByCustomerRefOrderByCreatedAtDescIdDesc(customerRef).stream()
                .map(booking -> BookingResponse.from(expireIfLapsed(booking, now)))
                .toList();
    }

    /**
     * Marks a lapsed pending hold EXPIRED so its owner sees the real state. The write takes the
     * same event-then-booking locks as payment and edits, and re-checks under them, so it cannot
     * race a payment that is confirming the hold.
     */
    private Booking expireIfLapsed(Booking booking, Instant now) {
        if (booking.getStatus() != BookingStatus.PENDING_PAYMENT || !booking.isExpired(now)) {
            return booking;
        }
        Booking locked = getOwnedBookingForUpdate(booking.getId(), booking.getCustomerRef());
        if (locked.getStatus() == BookingStatus.PENDING_PAYMENT && locked.isExpired(now)) {
            expire(locked);
        }
        return locked;
    }

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
                    seat.getSection() == null ? null : seat.getSection().getName(), sectionId, seatPriceOrNull(event, seat), status));
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
        List<PointItem> points = section.getPoints();
        if (section.getBookingMode() == SectionBookingMode.GENERAL_ADMISSION) {
            int capacity = section.getCapacity() != null ? section.getCapacity() : 0;
            long confirmed = bookingRepository.sumConfirmedQuantityBySection(event.getId(), section.getId());
            long held = bookingRepository.sumReservedQuantityBySection(event.getId(), section.getId(), now);
            long free = Math.max(0, capacity - (confirmed + held));
            return new SectionAvailabilityResponse(section.getId(), section.getName(),
                    section.getBookingMode(), price, serviceFee(section), capacity, free, held,
                    confirmed, section.getShapeKind(), points, section.getColor());
        }
        long[] tally = seatedTally.getOrDefault(section.getId(), new long[3]);
        int capacity = (int) (tally[0] + tally[1] + tally[2]);
        return new SectionAvailabilityResponse(section.getId(), section.getName(),
                section.getBookingMode(), price, serviceFee(section), capacity, tally[0], tally[1],
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
        int capacity = event.getHall().getCapacity();
        long available = Math.max(0, capacity - (confirmed + reserved));
        boolean soldOut = capacity - confirmed <= 0;
        return new EventAvailabilityResponse(eventId, false, capacity,
                confirmed, reserved, available, soldOut, generalAdmissionPrice(event));
    }

    // ------------------------------------------------------------------ helpers

    /** Consistent lock order shared by edits, payment initiation and cancellation. */
    private Booking getOwnedBookingForUpdate(Long bookingId, String customerRef) {
        Long eventId = bookingRepository.findEventId(bookingId)
                .orElseThrow(() -> ResourceNotFoundException.of("Booking", bookingId));
        eventRepository.findByIdForUpdate(eventId)
                .orElseThrow(() -> ResourceNotFoundException.of("Event", eventId));
        return bookingRepository.findByIdForUpdate(bookingId)
                .filter(booking -> booking.getCustomerRef().equals(customerRef))
                .orElseThrow(() -> ResourceNotFoundException.of("Booking", bookingId));
    }

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
        long confirmedUnits = bookingRepository.sumConfirmedQuantity(event.getId());
        // Event capacity is derived from the hall: its seats plus every GA section's capacity.
        if (confirmedUnits >= event.getHall().getCapacity()) {
            event.setStatus(EventStatus.SOLD_OUT);
        }
    }
}
