package com.bunq.javabackend.service;

import com.bunq.javabackend.dto.request.ExtractControlsRequestDTO;
import com.bunq.javabackend.dto.response.ControlResponseDTO;
import com.bunq.javabackend.exception.SessionNotFoundException;
import com.bunq.javabackend.helper.mapper.ControlMapper;
import com.bunq.javabackend.repository.ControlRepository;
import com.bunq.javabackend.repository.SessionRepository;
import com.bunq.javabackend.service.pipeline.PipelineContext;
import com.bunq.javabackend.service.pipeline.stage.ExtractControlsStage;
import com.bunq.javabackend.service.sse.SseEmitterService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ControlService {

    private final ControlRepository controlRepository;
    private final ExtractControlsStage extractControlsStage;
    private final SseEmitterService sseEmitterService;
    private final SessionRepository sessionRepository;

    public List<ControlResponseDTO> list(String sessionId) {
        return controlRepository.findBySessionId(sessionId).stream()
                .map(ControlMapper::toDto)
                .toList();
    }

    public ControlResponseDTO get(String id) {
        return controlRepository.findById(id)
                .map(ControlMapper::toDto)
                .orElseThrow(() -> new IllegalArgumentException("Control not found: " + id));
    }

    public void extract(ExtractControlsRequestDTO request) {
        sessionRepository.findById(request.getSessionId())
                .orElseThrow(() -> new SessionNotFoundException(request.getSessionId()));

        PipelineContext ctx = new PipelineContext(
                request.getSessionId(),
                null,
                null,
                List.of(),
                null,
                sseEmitterService
        );

        extractControlsStage.execute(ctx);
    }
}
