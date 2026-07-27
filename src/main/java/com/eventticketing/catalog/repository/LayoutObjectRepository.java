package com.eventticketing.catalog.repository;

import com.eventticketing.catalog.domain.LayoutObject;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LayoutObjectRepository extends JpaRepository<LayoutObject, Long> {

    List<LayoutObject> findByHallIdOrderByIdAsc(Long hallId);
}
