package com.eventticketing.reservation.repository;

import com.eventticketing.reservation.domain.Payment;
import com.eventticketing.reservation.domain.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByBookingId(Long bookingId);

    /** In-doubt payments not touched since {@code before} — candidates for reconciliation. */
    List<Payment> findByStatusAndUpdatedAtBefore(PaymentStatus status, Instant before);
}
