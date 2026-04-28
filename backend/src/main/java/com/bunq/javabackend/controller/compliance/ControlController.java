package com.bunq.javabackend.controller.compliance;

import com.bunq.javabackend.dto.request.ExtractControlsRequestDTO;
import com.bunq.javabackend.dto.response.ControlResponseDTO;
import com.bunq.javabackend.service.compliance.ControlService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class ControlController {

    private final ControlService controlService;

    @PostMapping("/controls/extract")
    public ResponseEntity<Void> extract(@Valid @RequestBody ExtractControlsRequestDTO request) {
        controlService.extract(request);
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/sessions/{id}/controls")
    public ResponseEntity<List<ControlResponseDTO>> list(@PathVariable String id) {
        return ResponseEntity.ok(controlService.list(id));
    }

    @GetMapping("/controls/{id}")
    public ResponseEntity<ControlResponseDTO> getById(@PathVariable String id) {
        try {
            return ResponseEntity.ok(controlService.get(id));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }
}
