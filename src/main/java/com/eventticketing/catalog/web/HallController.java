package com.eventticketing.catalog.web;

import com.eventticketing.catalog.dto.CreateHallRequest;
import com.eventticketing.catalog.dto.HallResponse;
import com.eventticketing.catalog.dto.HallSummaryResponse;
import com.eventticketing.catalog.service.HallService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/halls")
public class HallController {

    private final HallService hallService;

    public HallController(HallService hallService) {
        this.hallService = hallService;
    }

    @PostMapping
    public ResponseEntity<HallResponse> create(@Valid @RequestBody CreateHallRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(hallService.create(request));
    }

    @GetMapping
    public List<HallSummaryResponse> list() {
        return hallService.list();
    }

    @GetMapping("/{id}")
    public HallResponse get(@PathVariable Long id) {
        return hallService.get(id);
    }
}
