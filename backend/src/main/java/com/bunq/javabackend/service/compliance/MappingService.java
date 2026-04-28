package com.bunq.javabackend.service.compliance;

import com.bunq.javabackend.dto.request.ComputeMappingsRequestDTO;
import com.bunq.javabackend.dto.response.MappingResponseDTO;
import com.bunq.javabackend.exception.SessionNotFoundException;
import com.bunq.javabackend.helper.mapper.MappingMapper;
import com.bunq.javabackend.repository.MappingRepository;
import com.bunq.javabackend.repository.SessionRepository;
import com.bunq.javabackend.service.pipeline.PipelineContext;
import com.bunq.javabackend.service.pipeline.stage.MapObligationsControlsStage;
import com.bunq.javabackend.service.infra.sse.SseEmitterService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class MappingService {

    private final MappingRepository mappingRepository;
    private final MapObligationsControlsStage mapObligationsControlsStage;
    private final SseEmitterService sseEmitterService;
    private final SessionRepository sessionRepository;

    public List<MappingResponseDTO> list(String sessionId) {
        return mappingRepository.findBySessionId(sessionId).stream()
                .map(MappingMapper::toDto)
                .toList();
    }

    public MappingResponseDTO get(String id) {
        return mappingRepository.findById(id)
                .map(MappingMapper::toDto)
                .orElseThrow(() -> new IllegalArgumentException("Mapping not found: " + id));
    }

    public void compute(ComputeMappingsRequestDTO request) {
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

        mapObligationsControlsStage.execute(ctx);
    }
}
