package com.eventticketing.catalog.repository;

import com.eventticketing.catalog.domain.Section;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SectionRepository extends JpaRepository<Section, Long> {

    List<Section> findByHallIdOrderByIdAsc(Long hallId);
}
