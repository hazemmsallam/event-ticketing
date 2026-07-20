package com.eventticketing.catalog.repository;

import com.eventticketing.catalog.domain.Organizer;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrganizerRepository extends JpaRepository<Organizer, Long> {
}
