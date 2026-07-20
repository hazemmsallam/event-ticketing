package com.eventticketing.catalog.repository;

import com.eventticketing.catalog.domain.EventPricing;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EventPricingRepository extends JpaRepository<EventPricing, Long> {

    List<EventPricing> findByEventId(Long eventId);
}
