package com.eventticketing.catalog.web;

import com.eventticketing.catalog.dto.CreateOrganizerRequest;
import com.eventticketing.catalog.dto.OrganizerResponse;
import com.eventticketing.catalog.service.OrganizerService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/organizers")
public class OrganizerController {

    private final OrganizerService organizerService;

    public OrganizerController(OrganizerService organizerService) {
        this.organizerService = organizerService;
    }

    @PostMapping
    public ResponseEntity<OrganizerResponse> create(@Valid @RequestBody CreateOrganizerRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(organizerService.create(request));
    }

    @GetMapping
    public List<OrganizerResponse> list() {
        return organizerService.list();
    }

    @GetMapping("/{id}")
    public OrganizerResponse get(@PathVariable Long id) {
        return organizerService.get(id);
    }

    @PutMapping("/{id}")
    public OrganizerResponse update(@PathVariable Long id, @Valid @RequestBody CreateOrganizerRequest request) {
        return organizerService.update(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        organizerService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
