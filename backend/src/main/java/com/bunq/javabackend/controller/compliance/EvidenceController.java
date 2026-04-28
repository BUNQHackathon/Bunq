package com.bunq.javabackend.controller.compliance;

import com.bunq.javabackend.dto.request.EvidenceFinalizeRequest;
import com.bunq.javabackend.dto.request.EvidencePresignRequest;
import com.bunq.javabackend.dto.response.EvidencePresignResponse;
import com.bunq.javabackend.dto.response.EvidenceResponseDTO;
import com.bunq.javabackend.dto.response.sidecar.GraphDAG;
import com.bunq.javabackend.service.compliance.EvidenceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class EvidenceController {

    private final EvidenceService evidenceService;

    @GetMapping("/evidence/{id}")
    public ResponseEntity<EvidenceResponseDTO> get(@PathVariable String id) {
        return ResponseEntity.ok(evidenceService.get(id));
    }

    @GetMapping("/proof-tree/{mappingId}")
    public ResponseEntity<GraphDAG> getProofTree(@PathVariable String mappingId) {
        return ResponseEntity.ok(evidenceService.getProofTree(mappingId));
    }

    @GetMapping("/sessions/{id}/compliance-map")
    public ResponseEntity<GraphDAG> getComplianceMap(@PathVariable String id) {
        return ResponseEntity.ok(evidenceService.getComplianceMap(id));
    }

    @PostMapping("/sessions/{sessionId}/evidence/presign")
    public ResponseEntity<EvidencePresignResponse> presign(
            @PathVariable String sessionId,
            @Valid @RequestBody EvidencePresignRequest request) {
        return ResponseEntity.ok(evidenceService.presign(sessionId, request));
    }

    @PostMapping("/sessions/{sessionId}/evidence/finalize")
    public ResponseEntity<EvidenceResponseDTO> finalize(
            @PathVariable String sessionId,
            @Valid @RequestBody EvidenceFinalizeRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(evidenceService.finalize(sessionId, request));
    }
}
