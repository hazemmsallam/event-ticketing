package com.eventticketing.catalog.repository;

import com.eventticketing.catalog.domain.Event;
import com.eventticketing.catalog.domain.EventStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface EventRepository extends JpaRepository<Event, Long> {

    List<Event> findByStatusInOrderByStartAtAsc(Collection<EventStatus> statuses);

    /**
     * Loads an event with a pessimistic write lock. Used to serialize general-admission
     * capacity checks so a non-seated event cannot be oversold under concurrency.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Event e where e.id = :id")
    Optional<Event> findByIdForUpdate(Long id);
}
