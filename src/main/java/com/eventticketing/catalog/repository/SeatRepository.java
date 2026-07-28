package com.eventticketing.catalog.repository;

import com.eventticketing.catalog.domain.Seat;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SeatRepository extends JpaRepository<Seat, Long> {

    List<Seat> findByHallIdOrderByRowIndexAscSeatNumberAsc(Long hallId);

    long countByHallId(Long hallId);
}
