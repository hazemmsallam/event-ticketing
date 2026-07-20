package com.eventticketing.catalog.repository;

import com.eventticketing.catalog.domain.Seat;
import com.eventticketing.catalog.domain.SeatType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface SeatRepository extends JpaRepository<Seat, Long> {

    List<Seat> findByHallIdOrderByRowIndexAscSeatNumberAsc(Long hallId);

    long countByHallId(Long hallId);

    @Query("select distinct s.seatType from Seat s where s.hall.id = :hallId")
    List<SeatType> findDistinctSeatTypesByHallId(Long hallId);
}
