package com.bunq.javabackend.service.pipeline.stage;

import com.bunq.javabackend.client.SidecarClient;
import com.bunq.javabackend.dto.response.events.StageDeltaEvent;
import com.bunq.javabackend.exception.SidecarCommunicationException;
import com.bunq.javabackend.helper.mapper.SanctionHitMapper;
import com.bunq.javabackend.model.enums.SanctionMatchStatus;
import com.bunq.javabackend.model.sanction.Counterparty;
import com.bunq.javabackend.model.sanction.SanctionHit;
import com.bunq.javabackend.model.sanction.SanctionMatch;
import com.bunq.javabackend.model.sanction.SanctionsEntity;
import com.bunq.javabackend.repository.SanctionHitRepository;
import com.bunq.javabackend.repository.SanctionsEntityRepository;
import com.bunq.javabackend.service.pipeline.PipelineContext;
import com.bunq.javabackend.service.pipeline.PipelineStage;
import com.bunq.javabackend.service.pipeline.Stage;
import com.bunq.javabackend.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
@RequiredArgsConstructor
public class SanctionsScreenStage implements Stage {

    private final SidecarClient sidecarClient;
    private final SanctionHitRepository sanctionHitRepository;
    private final SanctionsEntityRepository sanctionsEntityRepository;

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

            List<SanctionHit> allHits = new ArrayList<>();
            List<Counterparty> needsSidecar = new ArrayList<>();

            for (Counterparty cp : counterparties) {
                List<SanctionHit> localHits = checkLocalTable(cp, ctx.getSessionId());
                if (!localHits.isEmpty()) {
                    allHits.addAll(localHits);
                    log.info("SanctionsScreenStage: local table hit for '{}' in session {}",
                            cp.getName(), ctx.getSessionId());
                } else {
                    needsSidecar.add(cp);
                }
            }

            if (!needsSidecar.isEmpty()) {
                List<SanctionHit> sidecarHits;
                try {
                    sidecarHits = sidecarClient.screenSanctions(
                            ctx.getSessionId(), needsSidecar, ctx.getBriefText());
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
                    sidecarHits = List.of();
                }
                allHits.addAll(sidecarHits);
            }

            for (SanctionHit hit : allHits) {
                sanctionHitRepository.save(hit);
                ctx.getSanctionHits().add(hit);
                ctx.getSseEmitterService().send(ctx.getSessionId(), "sanctions.hit",
                        SanctionHitMapper.toDto(hit));
            }

            log.info("SanctionsScreenStage: screened {} counterparties, {} hits for session {}",
                    counterparties.size(), allHits.size(), ctx.getSessionId());
        });
    }

    private List<SanctionHit> checkLocalTable(Counterparty cp, String sessionId) {
        if (cp.getName() == null || cp.getName().isBlank()) {
            return List.of();
        }
        try {
            String normalized = normalizeName(cp.getName());
            List<SanctionsEntity> matches = sanctionsEntityRepository.findByNormalizedName(normalized);
            if (matches.isEmpty()) {
                return List.of();
            }

            List<SanctionMatch> matchList = matches.stream().map(entity -> {
                SanctionMatch m = new SanctionMatch();
                m.setListSource(entity.getListSource());
                m.setEntityName(entity.getEntityName());
                m.setMatchScore(1.0);
                return m;
            }).toList();

            SanctionHit hit = new SanctionHit();
            hit.setId(IdGenerator.generateSanctionsHitId());
            hit.setSessionId(sessionId);
            hit.setScreenedAt(Instant.now());
            hit.setMatchStatus(SanctionMatchStatus.flagged);
            hit.setCounterparty(cp);
            hit.setHits(matchList);

            return List.of(hit);
        } catch (Exception e) {
            log.warn("SanctionsScreenStage: local table lookup failed for '{}', falling back to sidecar: {}",
                    cp.getName(), e.getMessage());
            return List.of();
        }
    }

    private static String normalizeName(String name) {
        return name.toLowerCase().trim()
                .replaceAll("[^a-z0-9 ]", "")
                .replaceAll("\\s+", " ");
    }
}
