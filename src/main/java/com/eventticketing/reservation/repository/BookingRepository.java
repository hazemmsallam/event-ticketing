package com.eventticketing.reservation.repository;

import com.eventticketing.reservation.domain.Booking;
import com.eventticketing.reservation.domain.BookingStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    @Query("select b.event.id from Booking b where b.id = :id")
    Optional<Long> findEventId(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Booking b where b.id = :id")
    Optional<Booking> findByIdForUpdate(@Param("id") Long id);

    List<Booking> findByStatusAndExpiresAtBefore(BookingStatus status, Instant time);

    /**
     * The caller's still-live hold, if they have one: PENDING_PAYMENT and not yet expired.
     *
     * <p>Expiry is evaluated here rather than relying on the sweeper, so a lapsed hold never
     * blocks a customer from booking again — the same lazy-expiry rule the rest of the service
     * uses. Backed by {@code idx_booking_customer_status_expiry}.
     */
    @Query("""
            select b
            from Booking b
            where b.customerRef = :customerRef
              and b.status = com.eventticketing.reservation.domain.BookingStatus.PENDING_PAYMENT
              and b.expiresAt > :now
            order by b.expiresAt desc
            """)
    List<Booking> findActiveHolds(@Param("customerRef") String customerRef, @Param("now") Instant now);

    /**
     * One bounded page of expired holds, oldest first. The sweeper works in batches so a
     * flash-sale expiry wave cannot lock and update thousands of rows in a single transaction.
     */
    List<Booking> findByStatusAndExpiresAtBeforeOrderByExpiresAtAsc(BookingStatus status, Instant time,
                                                                   Pageable pageable);

    /**
     * Ids of the given sections that already carry general-admission bookings. Such a section
     * cannot be deleted: its bookings are real sales, and {@code booking_ga_line.section_id}
     * references it.
     */
    @Query("""
            select distinct l.section.id
            from BookingGaLine l
            where l.section.id in :sectionIds
            """)
    List<Long> findBookedSectionIds(@Param("sectionIds") Collection<Long> sectionIds);

    List<Booking> findByEventIdAndStatusAndExpiresAtBefore(Long eventId, BookingStatus status, Instant time);

    // gaLines are left to lazy loading: fetching two bag collections at once is rejected by Hibernate.
    @EntityGraph(attributePaths = {"event", "bookingSeats", "bookingSeats.seat"})
    @Query("""
            select distinct b
            from Booking b
            where b.customerRef = :customerRef
            order by b.createdAt desc, b.id desc
            """)
    List<Booking> findByCustomerRefOrderByCreatedAtDescIdDesc(@Param("customerRef") String customerRef);

    /** Confirmed (paid) units for an event, seats and GA tickets together. */
    @Query("""
            select coalesce(sum(b.quantity), 0)
            from Booking b
            where b.event.id = :eventId
              and b.status = com.eventticketing.reservation.domain.BookingStatus.CONFIRMED
            """)
    long sumConfirmedQuantity(@Param("eventId") Long eventId);

    /** Reserved (pending, unexpired) units for an event, seats and GA tickets together. */
    @Query("""
            select coalesce(sum(b.quantity), 0)
            from Booking b
            where b.event.id = :eventId
              and b.status = com.eventticketing.reservation.domain.BookingStatus.PENDING_PAYMENT
              and b.expiresAt > :now
            """)
    long sumReservedQuantity(@Param("eventId") Long eventId, @Param("now") Instant now);

    /**
     * Confirmed (paid) tickets in one general-admission section of one event. Legacy lines with
     * no resolved section count against every section, so they can never cause an oversell.
     */
    @Query("""
            select coalesce(sum(l.quantity), 0)
            from BookingGaLine l
            where l.event.id = :eventId
              and (l.section.id = :sectionId or l.section is null)
              and l.booking.status = com.eventticketing.reservation.domain.BookingStatus.CONFIRMED
            """)
    long sumConfirmedQuantityBySection(@Param("eventId") Long eventId, @Param("sectionId") Long sectionId);

    /** Reserved (pending, unexpired) tickets in one general-admission section of one event. */
    @Query("""
            select coalesce(sum(l.quantity), 0)
            from BookingGaLine l
            where l.event.id = :eventId
              and (l.section.id = :sectionId or l.section is null)
              and l.booking.status = com.eventticketing.reservation.domain.BookingStatus.PENDING_PAYMENT
              and l.booking.expiresAt > :now
            """)
    long sumReservedQuantityBySection(@Param("eventId") Long eventId, @Param("sectionId") Long sectionId,
                                      @Param("now") Instant now);
}
