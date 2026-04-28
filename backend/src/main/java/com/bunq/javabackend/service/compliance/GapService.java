package com.bunq.javabackend.service.compliance;

import com.bunq.javabackend.dto.request.ScoreGapsRequestDTO;
import com.bunq.javabackend.dto.response.GapResponseDTO;
import com.bunq.javabackend.exception.SessionNotFoundException;
import com.bunq.javabackend.helper.mapper.GapMapper;
import com.bunq.javabackend.repository.GapRepository;
import com.bunq.javabackend.repository.SessionRepository;
import com.bunq.javabackend.service.pipeline.PipelineContext;
import com.bunq.javabackend.service.pipeline.stage.GapAnalyzeStage;
import com.bunq.javabackend.service.infra.sse.SseEmitterService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class GapService {

    private final GapRepository gapRepository;
    private final GapAnalyzeStage gapAnalyzeStage;
    private final SseEmitterService sseEmitterService;
    private final SessionRepository sessionRepository;

    public List<GapResponseDTO> list(String sessionId) {
        return gapRepository.findBySessionId(sessionId).stream()
                .map(GapMapper::toDto)
                .toList();
    }

    public GapResponseDTO get(String id) {
        return gapRepository.findById(id)
                .map(GapMapper::toDto)
                .orElseThrow(() -> new IllegalArgumentException("Gap not found: " + id));
    }

    public void score(ScoreGapsRequestDTO request) {
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

        gapAnalyzeStage.execute(ctx);
    }
}
