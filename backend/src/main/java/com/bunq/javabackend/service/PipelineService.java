package com.bunq.javabackend.service;

import com.bunq.javabackend.dto.request.PipelineStartRequestDTO;
import com.bunq.javabackend.exception.SessionNotFoundException;
import com.bunq.javabackend.repository.SessionRepository;
import com.bunq.javabackend.service.pipeline.PipelineOrchestrator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PipelineService {

    private final PipelineOrchestrator pipelineOrchestrator;
    private final SessionRepository sessionRepository;

    public void start(String sessionId, PipelineStartRequestDTO request) {
        sessionRepository.findById(sessionId)
                .orElseThrow(() -> new SessionNotFoundException(sessionId));
        pipelineOrchestrator.start(sessionId, request);
    }
}
