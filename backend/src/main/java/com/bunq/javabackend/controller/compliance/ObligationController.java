package com.bunq.javabackend.controller.compliance;

import com.bunq.javabackend.dto.request.ExtractObligationsRequestDTO;
import com.bunq.javabackend.dto.response.ObligationResponseDTO;
import com.bunq.javabackend.service.compliance.ObligationService;
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
public class ObligationController {

    private final ObligationService obligationService;

    @PostMapping("/obligations/extract")
    public ResponseEntity<Void> extract(@Valid @RequestBody ExtractObligationsRequestDTO request) {
        obligationService.extract(request);
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/sessions/{id}/obligations")
    public ResponseEntity<List<ObligationResponseDTO>> list(@PathVariable String id) {
        return ResponseEntity.ok(obligationService.list(id));
    }

    @GetMapping("/obligations/{id}")
    public ResponseEntity<ObligationResponseDTO> getById(@PathVariable String id) {
        try {
            return ResponseEntity.ok(obligationService.get(id));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }
}
