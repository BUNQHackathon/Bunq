package com.bunq.javabackend.service.pipeline.stage;

import com.bunq.javabackend.client.SidecarClient;
import com.bunq.javabackend.dto.response.events.StageDeltaEvent;
import com.bunq.javabackend.exception.SidecarCommunicationException;
import com.bunq.javabackend.helper.mapper.SanctionHitMapper;
import com.bunq.javabackend.model.sanction.Counterparty;
import com.bunq.javabackend.model.sanction.SanctionHit;
import com.bunq.javabackend.repository.SanctionHitRepository;
import com.bunq.javabackend.service.pipeline.PipelineContext;
import com.bunq.javabackend.service.pipeline.PipelineStage;
import com.bunq.javabackend.service.pipeline.Stage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
@RequiredArgsConstructor
public class SanctionsScreenStage implements Stage {

    private final SidecarClient sidecarClient;
    private final SanctionHitRepository sanctionHitRepository;

    @Override
    public PipelineStage stage() {
        return PipelineStage.SANCTIONS_SCREEN;
    }

    @Override
    public CompletableFuture<Void> execute(PipelineContext ctx) {
        return CompletableFuture.runAsync(() -> {
            List<Counterparty> counterparties = ctx.getCounterparties();
            if (counterparties == null || counterparties.isEmpty()) {
                log.info("SanctionsScreenStage: no counterparties for session {}, skipping", ctx.getSessionId());
                ctx.getSseEmitterService().send(ctx.getSessionId(), StageDeltaEvent.builder()
                        .sessionId(ctx.getSessionId())
                        .timestamp(Instant.now())
                        .stage(PipelineStage.SANCTIONS_SCREEN)
                        .itemType("stage.skipped")
                        .item(Map.of("reason", "no counterparties provided"))
                        .build());
                return;
            }

            List<SanctionHit> hits;
            try {
                hits = sidecarClient.screenSanctions(
                        ctx.getSessionId(), counterparties, ctx.getBriefText());
            } catch (SidecarCommunicationException e) {
                log.warn("SanctionsScreenStage: sidecar unreachable for session {}, skipping sanctions screening: {}",
                        ctx.getSessionId(), e.getMessage());
                ctx.getSseEmitterService().send(ctx.getSessionId(), StageDeltaEvent.builder()
                        .sessionId(ctx.getSessionId())
                        .timestamp(Instant.now())
                        .stage(PipelineStage.SANCTIONS_SCREEN)
                        .itemType("sanctions.degraded")
                        .item(Map.of("reason", "sidecar unreachable"))
                        .build());
                hits = List.of();
            }

            for (SanctionHit hit : hits) {
                sanctionHitRepository.save(hit);
                ctx.getSanctionHits().add(hit);
                ctx.getSseEmitterService().send(ctx.getSessionId(), "sanctions.hit",
                        SanctionHitMapper.toDto(hit));
            }

            log.info("SanctionsScreenStage: screened {} counterparties, {} hits for session {}",
                    counterparties.size(), hits.size(), ctx.getSessionId());
        });
    }
}
