package com.bunq.javabackend.controller.ai;

import com.bunq.javabackend.dto.request.PipelineStartRequestDTO;
import com.bunq.javabackend.model.observability.SessionCost;
import com.bunq.javabackend.service.PipelineService;
import com.bunq.javabackend.service.infra.sse.SseEmitterService;
import com.bunq.javabackend.service.observability.SessionCostService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/sessions/{id}")
@RequiredArgsConstructor
public class PipelineController {

    private final SseEmitterService sseEmitterService;
    private final SessionCostService sessionCostService;
    private final PipelineService pipelineService;

    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable String id) {
        return sseEmitterService.register(id);
    }

    @PostMapping("/pipeline/start")
    public ResponseEntity<Void> startPipeline(
            @PathVariable String id,
            @Valid @RequestBody(required = false) PipelineStartRequestDTO request) {
        PipelineStartRequestDTO dto = request != null ? request : new PipelineStartRequestDTO();
        pipelineService.start(id, dto);
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }

    /** B11: Returns the accumulated Bedrock cost for this session. 404 if no calls recorded yet. */
    @GetMapping("/cost")
    public ResponseEntity<SessionCost> cost(@PathVariable String id) {
        SessionCost cost = sessionCostService.get(id);
        if (cost == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(cost);
    }
}
