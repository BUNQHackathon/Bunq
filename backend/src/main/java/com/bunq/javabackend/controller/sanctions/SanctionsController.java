package com.bunq.javabackend.controller.sanctions;

import com.bunq.javabackend.dto.request.ScreenSanctionsRequestDTO;
import com.bunq.javabackend.dto.response.SanctionHitResponseDTO;
import com.bunq.javabackend.service.sanctions.SanctionsService;
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
public class SanctionsController {

    private final SanctionsService sanctionsService;

    @PostMapping("/sanctions/screen")
    public ResponseEntity<Void> screen(@Valid @RequestBody ScreenSanctionsRequestDTO request) {
        sanctionsService.screen(request);
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/sessions/{id}/sanctions")
    public ResponseEntity<List<SanctionHitResponseDTO>> list(@PathVariable String id) {
        return ResponseEntity.ok(sanctionsService.list(id));
    }
}
