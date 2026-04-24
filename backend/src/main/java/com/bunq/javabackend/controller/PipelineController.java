package com.bunq.javabackend.controller;

import com.bunq.javabackend.dto.request.PipelineStartRequestDTO;
import com.bunq.javabackend.service.PipelineService;
import com.bunq.javabackend.service.sse.SseEmitterService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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

    private final PipelineService pipelineService;
    private final SseEmitterService sseEmitterService;

    @PostMapping("/pipeline/start")
    public ResponseEntity<Void> start(
            @PathVariable String id,
            @Valid @RequestBody(required = false) PipelineStartRequestDTO request) {

        pipelineService.start(id, request != null ? request : new PipelineStartRequestDTO());
        return ResponseEntity.accepted().build();
    }

    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable String id) {
        return sseEmitterService.register(id);
    }
}
