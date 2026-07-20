package com.eventticketing.catalog.repository;

import com.eventticketing.catalog.domain.Event;
import com.eventticketing.catalog.domain.EventStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface EventRepository extends JpaRepository<Event, Long> {

    List<Event> findByStatusInOrderByStartAtAsc(Collection<EventStatus> statuses);
}
