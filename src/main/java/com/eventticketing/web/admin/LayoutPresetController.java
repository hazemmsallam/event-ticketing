package com.eventticketing.web.admin;

import com.eventticketing.catalog.dto.CreateLayoutPresetRequest;
import com.eventticketing.catalog.dto.LayoutPresetResponse;
import com.eventticketing.catalog.dto.LayoutPresetSummaryResponse;
import com.eventticketing.catalog.service.LayoutPresetService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Admin: save and reuse layout presets (named blocks of sections, seats and tables). */
@RestController
@RequestMapping("/api/layout-presets")
public class LayoutPresetController {

    private final LayoutPresetService layoutPresetService;

    public LayoutPresetController(LayoutPresetService layoutPresetService) {
        this.layoutPresetService = layoutPresetService;
    }

    @PostMapping
    public ResponseEntity<LayoutPresetResponse> create(@Valid @RequestBody CreateLayoutPresetRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(layoutPresetService.create(request));
    }

    @GetMapping
    public List<LayoutPresetSummaryResponse> list() {
        return layoutPresetService.list();
    }

    @GetMapping("/{id}")
    public LayoutPresetResponse get(@PathVariable Long id) {
        return layoutPresetService.get(id);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        layoutPresetService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
