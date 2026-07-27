package com.eventticketing.reservation.repository;

import com.eventticketing.reservation.domain.Ticket;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TicketRepository extends JpaRepository<Ticket, Long> {

    List<Ticket> findByBookingIdOrderByIdAsc(Long bookingId);
}
