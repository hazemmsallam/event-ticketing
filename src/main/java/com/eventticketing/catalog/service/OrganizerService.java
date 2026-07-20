package com.eventticketing.catalog.service;

import com.eventticketing.catalog.domain.Organizer;
import com.eventticketing.catalog.dto.CreateOrganizerRequest;
import com.eventticketing.catalog.dto.OrganizerResponse;
import com.eventticketing.catalog.repository.OrganizerRepository;
import com.eventticketing.common.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class OrganizerService {

    private final OrganizerRepository organizerRepository;

    public OrganizerService(OrganizerRepository organizerRepository) {
        this.organizerRepository = organizerRepository;
    }

    @Transactional
    public OrganizerResponse create(CreateOrganizerRequest request) {
        Organizer organizer = new Organizer();
        organizer.setName(request.name());
        organizer.setEmail(request.email());
        organizer.setPhone(request.phone());
        return OrganizerResponse.from(organizerRepository.save(organizer));
    }

    @Transactional(readOnly = true)
    public List<OrganizerResponse> list() {
        return organizerRepository.findAll().stream().map(OrganizerResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public OrganizerResponse get(Long id) {
        return OrganizerResponse.from(getEntity(id));
    }

    @Transactional(readOnly = true)
    public Organizer getEntity(Long id) {
        return organizerRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("Organizer", id));
    }
}
