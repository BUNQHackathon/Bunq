package com.bunq.javabackend.controller;

import com.bunq.javabackend.client.SidecarClient;
import com.bunq.javabackend.dto.request.CreateLaunchRequestDTO;
import com.bunq.javabackend.dto.response.DocumentResponseDTO;
import com.bunq.javabackend.dto.response.JurisdictionRunResponseDTO;
import com.bunq.javabackend.dto.response.LaunchResponseDTO;
import com.bunq.javabackend.dto.response.LaunchSummaryDTO;
import com.bunq.javabackend.dto.response.sidecar.GraphDAG;
import com.bunq.javabackend.exception.NotFoundException;
import com.bunq.javabackend.helper.mapper.DocumentMapper;
import com.bunq.javabackend.helper.mapper.LaunchMapper;
import com.bunq.javabackend.repository.JurisdictionRunRepository;
import com.bunq.javabackend.repository.LaunchRepository;
import com.bunq.javabackend.service.AutoDocService;
import com.bunq.javabackend.service.LaunchService;
import com.bunq.javabackend.service.ProofPackService;
import com.bunq.javabackend.service.sse.SseEmitterService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/launches")
@RequiredArgsConstructor
public class LaunchController {

    private final LaunchService launchService;
    private final AutoDocService autoDocService;
    private final LaunchRepository launchRepository;
    private final ProofPackService proofPackService;
    private final JurisdictionRunRepository jurisdictionRunRepository;
    private final SidecarClient sidecarClient;
    private final SseEmitterService sseEmitterService;

    @PostMapping
    public ResponseEntity<LaunchResponseDTO> createLaunch(@Valid @RequestBody CreateLaunchRequestDTO request) {
        var launch = launchService.createLaunch(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(launchService.getLaunch(launch.getId()));
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

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteLaunch(@PathVariable String id) {
        launchService.deleteLaunch(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/rerun-failed")
    public ResponseEntity<List<JurisdictionRunResponseDTO>> rerunFailed(@PathVariable String id) {
        var runs = launchService.rerunFailed(id);
        var dtos = runs.stream().map(LaunchMapper::toDto).toList();
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(dtos);
    }

    @PostMapping("/{id}/jurisdictions/{code}/run")
    public ResponseEntity<JurisdictionRunResponseDTO> runJurisdiction(
            @PathVariable String id,
            @PathVariable String code) {
        var run = launchService.runJurisdiction(id, code);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(LaunchMapper.toDto(run));
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

    @GetMapping(value = "/{launchId}/jurisdictions/{code}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter jurisdictionStream(
            @PathVariable String launchId,
            @PathVariable String code) {
        var run = jurisdictionRunRepository.findByLaunchIdAndCode(launchId, code)
                .orElseThrow(() -> new NotFoundException(
                        "JurisdictionRun not found: launch=" + launchId + " code=" + code));
        String sessionId = run.getCurrentSessionId();
        if (sessionId == null) {
            SseEmitter emitter = new SseEmitter(0L);
            try {
                emitter.send(SseEmitter.event().name("error").data("{\"message\":\"no active session\"}"));
            } catch (Exception ignored) {
            }
            emitter.complete();
            return emitter;
        }
        return sseEmitterService.register(sessionId);
    }

    @GetMapping("/{id}/jurisdictions/{code}/compliance-map")
    public ResponseEntity<GraphDAG> getComplianceMap(@PathVariable String id, @PathVariable String code) {
        var run = jurisdictionRunRepository.findByLaunchIdAndCode(id, code)
                .orElseThrow(() -> new NotFoundException(
                        "JurisdictionRun not found: launch=" + id + " code=" + code));
        if (run.getCurrentSessionId() == null) {
            throw new IllegalStateException("Analysis in progress — compliance map not ready");
        }
        return ResponseEntity.ok(sidecarClient.getComplianceMap(run.getCurrentSessionId()));
    }
}
