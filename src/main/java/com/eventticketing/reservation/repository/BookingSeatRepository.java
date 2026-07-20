package com.eventticketing.reservation.repository;

import com.eventticketing.reservation.domain.BookingSeat;
import com.eventticketing.reservation.domain.BookingSeatStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface BookingSeatRepository extends JpaRepository<BookingSeat, Long> {

    /** Active holds (HELD/BOOKED) among the given seats for an event; used for pre-checks. */
    @Query("""
            select bs from BookingSeat bs
            where bs.event.id = :eventId
              and bs.seat.id in :seatIds
              and bs.status in :statuses
            """)
    List<BookingSeat> findForSeats(@Param("eventId") Long eventId,
                                   @Param("seatIds") Collection<Long> seatIds,
                                   @Param("statuses") Collection<BookingSeatStatus> statuses);

    /**
     * All active seat rows for an event, with the owning booking and seat eagerly loaded so
     * the live seat map can resolve status and expiry without extra queries.
     */
    @Query("""
            select bs from BookingSeat bs
            join fetch bs.booking b
            join fetch bs.seat s
            where bs.event.id = :eventId
              and bs.status in :statuses
            """)
    List<BookingSeat> findActiveForEvent(@Param("eventId") Long eventId,
                                         @Param("statuses") Collection<BookingSeatStatus> statuses);

    long countByEventIdAndStatus(Long eventId, BookingSeatStatus status);
}
