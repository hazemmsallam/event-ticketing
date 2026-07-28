package com.eventticketing.catalog.repository;

import com.eventticketing.catalog.domain.Section;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface SectionRepository extends JpaRepository<Section, Long> {

    List<Section> findByHallIdOrderByIdAsc(Long hallId);

    /**
     * Locks a single section for the duration of a general-admission capacity check. Capacity is a
     * property of the section, so locking the section — rather than the whole event — lets
     * different sections of the same event sell concurrently instead of serialising on one row.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Section s where s.id = :id")
    Optional<Section> findByIdForUpdate(Long id);
}
