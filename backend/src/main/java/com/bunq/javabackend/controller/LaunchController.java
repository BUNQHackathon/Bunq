package com.bunq.javabackend.controller;

import com.bunq.javabackend.dto.request.CreateLaunchRequestDTO;
import com.bunq.javabackend.dto.response.DocumentResponseDTO;
import com.bunq.javabackend.dto.response.JurisdictionRunResponseDTO;
import com.bunq.javabackend.dto.response.LaunchResponseDTO;
import com.bunq.javabackend.dto.response.LaunchSummaryDTO;
import com.bunq.javabackend.exception.NotFoundException;
import com.bunq.javabackend.helper.mapper.DocumentMapper;
import com.bunq.javabackend.repository.LaunchRepository;
import com.bunq.javabackend.service.AutoDocService;
import com.bunq.javabackend.service.LaunchService;
import com.bunq.javabackend.service.ProofPackService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

import static com.bunq.javabackend.helper.mapper.LaunchMapper.toDto;

@RestController
@RequestMapping("/launches")
@RequiredArgsConstructor
public class LaunchController {

    private final LaunchService launchService;
    private final AutoDocService autoDocService;
    private final LaunchRepository launchRepository;
    private final ProofPackService proofPackService;

    @PostMapping
    public ResponseEntity<LaunchSummaryDTO> createLaunch(@Valid @RequestBody CreateLaunchRequestDTO request) {
        var launch = launchService.createLaunch(request);
        var summary = launchService.toSummaryWithCount(launch);
        return ResponseEntity.status(HttpStatus.CREATED).body(summary);
    }

    @GetMapping
    public ResponseEntity<List<LaunchSummaryDTO>> listLaunches() {
        var launches = launchService.listLaunches();
        var summaries = launches.stream()
                .map(launchService::toSummaryWithCount)
                .toList();
        return ResponseEntity.ok(summaries);
    }

    @GetMapping("/{id}")
    public ResponseEntity<LaunchResponseDTO> getLaunch(@PathVariable String id) {
        return ResponseEntity.ok(launchService.getLaunch(id));
    }

    @PostMapping("/{id}/jurisdictions/{code}")
    public ResponseEntity<JurisdictionRunResponseDTO> addJurisdiction(
            @PathVariable String id,
            @PathVariable String code) {
        var run = launchService.addJurisdiction(id, code);
        return ResponseEntity.status(HttpStatus.CREATED).body(toDto(run));
    }

    @PostMapping("/{id}/jurisdictions/{code}/run")
    public ResponseEntity<JurisdictionRunResponseDTO> runJurisdiction(
            @PathVariable String id,
            @PathVariable String code) {
        var run = launchService.runJurisdiction(id, code);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(toDto(run));
    }

    @GetMapping("/{id}/auto-docs")
    public ResponseEntity<List<DocumentResponseDTO>> autoDocs(
            @PathVariable String id,
            @RequestParam("j") String jurisdictionCode) {
        launchRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Launch not found: " + id));
        List<DocumentResponseDTO> docs = autoDocService.forJurisdiction(jurisdictionCode)
                .stream()
                .map(DocumentMapper::toDto)
                .toList();
        return ResponseEntity.ok(docs);
    }

    @GetMapping("/{id}/jurisdictions/{code}/proof-pack")
    public ResponseEntity<byte[]> getProofPack(
            @PathVariable String id,
            @PathVariable String code) {
        var bytes = proofPackService.generate(id, code);
        var ts = Instant.now().toString().replace(":", "-");
        var filename = "proof-pack-" + id + "-" + code + "-" + ts + ".zip";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "application/zip")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(bytes);
    }
}
