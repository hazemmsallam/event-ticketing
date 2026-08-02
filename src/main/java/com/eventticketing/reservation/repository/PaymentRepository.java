package com.eventticketing.reservation.repository;

import com.eventticketing.reservation.domain.Payment;
import com.eventticketing.reservation.domain.PaymentStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByBookingId(Long bookingId);

    /**
     * The reconciliation work queue, honouring exponential backoff.
     *
     * <p>A payment qualifies when it is still in doubt, has been so for longer than the grace
     * period, and either has never been attempted or its backoff has elapsed. The cut-off is
     * supplied by the caller because the delay depends on the attempt count.
     *
     * <p>Ordered oldest-first so a backlog drains fairly rather than starving the earliest
     * payments, and paged so one tick can never pull an unbounded set.
     */
    @Query("""
            select p
            from Payment p
            where p.status in (com.eventticketing.reservation.domain.PaymentStatus.INITIATED,
                               com.eventticketing.reservation.domain.PaymentStatus.REFUND_PENDING)
              and p.updatedAt < :inDoubtSince
              and (p.lastReconcileAt is null or p.lastReconcileAt < :retryBefore)
            order by p.updatedAt asc
            """)
    List<Payment> findReconciliationQueue(@Param("inDoubtSince") Instant inDoubtSince,
                                          @Param("retryBefore") Instant retryBefore,
                                          Pageable pageable);

    /** Dead-lettered payments awaiting an operator, newest first. */
    List<Payment> findByStatusOrderByUpdatedAtDesc(PaymentStatus status);

    long countByStatus(PaymentStatus status);
}
