package com.bunq.javabackend.service;

import com.bunq.javabackend.dto.request.ScreenSanctionsRequestDTO;
import com.bunq.javabackend.dto.response.CounterpartyDTO;
import com.bunq.javabackend.dto.response.SanctionHitResponseDTO;
import com.bunq.javabackend.exception.SessionNotFoundException;
import com.bunq.javabackend.helper.mapper.SanctionHitMapper;
import com.bunq.javabackend.model.sanction.Counterparty;
import com.bunq.javabackend.model.enums.CounterpartyType;
import com.bunq.javabackend.repository.SanctionHitRepository;
import com.bunq.javabackend.repository.SessionRepository;
import com.bunq.javabackend.service.pipeline.PipelineContext;
import com.bunq.javabackend.service.pipeline.stage.SanctionsScreenStage;
import com.bunq.javabackend.service.sse.SseEmitterService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class SanctionsService {

    private final SanctionHitRepository sanctionHitRepository;
    private final SanctionsScreenStage sanctionsScreenStage;
    private final SseEmitterService sseEmitterService;
    private final SessionRepository sessionRepository;

    public List<SanctionHitResponseDTO> list(String sessionId) {
        return sanctionHitRepository.findBySessionId(sessionId).stream()
                .map(SanctionHitMapper::toDto)
                .toList();
    }

    public void screen(ScreenSanctionsRequestDTO request) {
        sessionRepository.findById(request.getSessionId())
                .orElseThrow(() -> new SessionNotFoundException(request.getSessionId()));

        List<Counterparty> counterparties = mapCounterparties(request.getCounterparties());

        PipelineContext ctx = new PipelineContext(
                request.getSessionId(),
                null,
                null,
                counterparties,
                request.getBriefText(),
                sseEmitterService
        );

        sanctionsScreenStage.execute(ctx);
    }

    private List<Counterparty> mapCounterparties(List<CounterpartyDTO> dtos) {
        if (dtos == null) return List.of();
        return dtos.stream().map(dto -> {
            Counterparty cp = new Counterparty();
            cp.setName(dto.getName());
            cp.setCountry(dto.getCountry());
            if (dto.getType() != null) {
                try { cp.setType(CounterpartyType.valueOf(dto.getType().toLowerCase())); }
                catch (Exception ignored) {}
            }
            return cp;
        }).toList();
    }
}
