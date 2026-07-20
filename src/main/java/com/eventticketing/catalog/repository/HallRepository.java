package com.eventticketing.catalog.repository;

import com.eventticketing.catalog.domain.Hall;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HallRepository extends JpaRepository<Hall, Long> {
}
