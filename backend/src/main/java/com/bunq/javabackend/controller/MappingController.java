package com.bunq.javabackend.controller;

import com.bunq.javabackend.dto.request.ComputeMappingsRequestDTO;
import com.bunq.javabackend.dto.response.MappingResponseDTO;
import com.bunq.javabackend.service.MappingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class MappingController {

    private final MappingService mappingService;

    @PostMapping("/mappings/compute")
    public ResponseEntity<Void> compute(@Valid @RequestBody ComputeMappingsRequestDTO request) {
        mappingService.compute(request);
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/sessions/{id}/mappings")
    public ResponseEntity<List<MappingResponseDTO>> list(@PathVariable String id) {
        return ResponseEntity.ok(mappingService.list(id));
    }
}
