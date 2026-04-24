package com.bunq.javabackend.service;

import com.bunq.javabackend.dto.request.ExtractObligationsRequestDTO;
import com.bunq.javabackend.dto.response.ObligationResponseDTO;
import com.bunq.javabackend.exception.SessionNotFoundException;
import com.bunq.javabackend.helper.mapper.ObligationMapper;
import com.bunq.javabackend.model.sanction.Counterparty;
import com.bunq.javabackend.repository.ObligationRepository;
import com.bunq.javabackend.repository.SessionRepository;
import com.bunq.javabackend.service.pipeline.PipelineContext;
import com.bunq.javabackend.service.pipeline.stage.ExtractObligationsStage;
import com.bunq.javabackend.service.sse.SseEmitterService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ObligationService {

    private final ObligationRepository obligationRepository;
    private final ExtractObligationsStage extractObligationsStage;
    private final SseEmitterService sseEmitterService;
    private final SessionRepository sessionRepository;

    public List<ObligationResponseDTO> list(String sessionId) {
        return obligationRepository.findBySessionId(sessionId).stream()
                .map(ObligationMapper::toDto)
                .toList();
    }

    public ObligationResponseDTO get(String id) {
        return obligationRepository.findById(id)
                .map(ObligationMapper::toDto)
                .orElseThrow(() -> new IllegalArgumentException("Obligation not found: " + id));
    }

    public void extract(ExtractObligationsRequestDTO request) {
        sessionRepository.findById(request.getSessionId())
                .orElseThrow(() -> new SessionNotFoundException(request.getSessionId()));

        String regulation = request.getRegulationChunk() != null
                ? request.getRegulationChunk().getText()
                : null;

        PipelineContext ctx = new PipelineContext(
                request.getSessionId(),
                regulation,
                null,
                List.of(),
                null,
                sseEmitterService
        );

        extractObligationsStage.execute(ctx);
    }
}
