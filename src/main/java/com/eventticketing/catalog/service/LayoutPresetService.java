package com.eventticketing.catalog.service;

import com.eventticketing.catalog.domain.LayoutPreset;
import com.eventticketing.catalog.dto.CreateLayoutPresetRequest;
import com.eventticketing.catalog.dto.LayoutPresetResponse;
import com.eventticketing.catalog.dto.LayoutPresetSummaryResponse;
import com.eventticketing.catalog.dto.PresetMembers;
import com.eventticketing.catalog.repository.LayoutPresetRepository;
import com.eventticketing.common.exception.BusinessRuleException;
import com.eventticketing.common.exception.ConflictException;
import com.eventticketing.common.exception.ResourceNotFoundException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Saves and serves reusable layout presets. The members are persisted as a JSON document so a
 * preset stays independent of the hall it was captured from (see
 * {@link com.eventticketing.catalog.domain.LayoutPreset}).
 */
@Service
public class LayoutPresetService {

    private final LayoutPresetRepository repository;
    private final ObjectMapper objectMapper;

    public LayoutPresetService(LayoutPresetRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public LayoutPresetResponse create(CreateLayoutPresetRequest request) {
        String name = request.name().trim();
        if (request.members().total() == 0) {
            throw new BusinessRuleException("Select at least one section, seat or table to save as a preset.");
        }
        if (repository.existsByNameIgnoreCase(name)) {
            throw new ConflictException("A preset named \"" + name + "\" already exists.");
        }

        LayoutPreset preset = new LayoutPreset();
        preset.setName(name);
        preset.setDescription(request.description() != null && !request.description().isBlank()
                ? request.description().trim() : null);
        preset.setWidth(request.width());
        preset.setHeight(request.height());
        preset.setPayload(writeMembers(request.members()));
        return LayoutPresetResponse.from(repository.save(preset), request.members());
    }

    @Transactional(readOnly = true)
    public List<LayoutPresetSummaryResponse> list() {
        return repository.findAllByOrderByNameAsc().stream()
                .map(p -> LayoutPresetSummaryResponse.from(p, readMembers(p.getPayload())))
                .toList();
    }

    @Transactional(readOnly = true)
    public LayoutPresetResponse get(Long id) {
        LayoutPreset preset = repository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("LayoutPreset", id));
        return LayoutPresetResponse.from(preset, readMembers(preset.getPayload()));
    }

    @Transactional
    public void delete(Long id) {
        if (!repository.existsById(id)) {
            throw ResourceNotFoundException.of("LayoutPreset", id);
        }
        repository.deleteById(id);
    }

    private String writeMembers(PresetMembers members) {
        try {
            return objectMapper.writeValueAsString(members);
        } catch (Exception e) {
            throw new BusinessRuleException("Invalid preset contents.");
        }
    }

    /** A preset whose stored JSON cannot be parsed is surfaced as empty rather than breaking the list. */
    private PresetMembers readMembers(String json) {
        try {
            return objectMapper.readValue(json, PresetMembers.class);
        } catch (Exception e) {
            return new PresetMembers(List.of(), List.of(), List.of());
        }
    }
}
