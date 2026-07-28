package com.eventticketing.catalog.repository;

import com.eventticketing.catalog.domain.EventPricing;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface EventPricingRepository extends JpaRepository<EventPricing, Long> {

    List<EventPricing> findByEventId(Long eventId);

    /**
     * Removes the price lines of sections that are being deleted. A price for a section that no
     * longer exists is meaningless, and the {@code event_pricing.section_id} FK would otherwise
     * block the section's deletion.
     */
    void deleteBySectionIdIn(Collection<Long> sectionIds);
}
