package com.eventticketing.catalog.repository;

import com.eventticketing.catalog.domain.LayoutPreset;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LayoutPresetRepository extends JpaRepository<LayoutPreset, Long> {

    /** Preset names are unique and used as the picker label, so matching ignores case. */
    boolean existsByNameIgnoreCase(String name);

    List<LayoutPreset> findAllByOrderByNameAsc();
}
