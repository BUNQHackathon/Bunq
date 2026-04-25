package com.bunq.javabackend.service.pipeline.stage;

import com.bunq.javabackend.helper.mapper.GapMapper;
import com.bunq.javabackend.model.gap.Gap;
import com.bunq.javabackend.model.mapping.Mapping;
import com.bunq.javabackend.model.obligation.Obligation;
import com.bunq.javabackend.model.enums.BedrockModel;
import com.bunq.javabackend.model.enums.GapStatus;
import com.bunq.javabackend.model.enums.GapType;
import com.bunq.javabackend.repository.GapRepository;
import com.bunq.javabackend.repository.MappingRepository;
import com.bunq.javabackend.repository.ObligationRepository;
import com.bunq.javabackend.service.bedrock.GapScore;
import com.bunq.javabackend.service.bedrock.GapScorer;
import com.bunq.javabackend.service.bedrock.MatchableObligation;
import com.bunq.javabackend.service.pipeline.PipelineContext;
import com.bunq.javabackend.service.pipeline.PipelineStage;
import com.bunq.javabackend.service.pipeline.Stage;
import com.bunq.javabackend.util.IdGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

@Slf4j
@Service
public class GapAnalyzeStage implements Stage {

    private final GapScorer gapScorer;
    private final GapRepository gapRepository;
    private final ObligationRepository obligationRepository;
    private final MappingRepository mappingRepository;
    private final Executor pipelineExecutor;

    public GapAnalyzeStage(GapScorer gapScorer, GapRepository gapRepository,
                           ObligationRepository obligationRepository, MappingRepository mappingRepository,
                           @Qualifier("pipelineExecutor") Executor pipelineExecutor) {
        this.gapScorer = gapScorer;
        this.gapRepository = gapRepository;
        this.obligationRepository = obligationRepository;
        this.mappingRepository = mappingRepository;
        this.pipelineExecutor = pipelineExecutor;
    }

    @Override
    public PipelineStage stage() {
        return PipelineStage.GAP_ANALYZE;
    }

    @Override
    public CompletableFuture<Void> execute(PipelineContext ctx) {
        return CompletableFuture.runAsync(() -> {
            List<Obligation> obligations = ctx.getObligations();
            if (obligations.isEmpty()) {
                obligations = obligationRepository.findBySessionId(ctx.getSessionId());
            }

            List<Mapping> mappings = ctx.getMappings();
            if (mappings.isEmpty()) {
                mappings = mappingRepository.findBySessionId(ctx.getSessionId());
            }

            Set<String> coveredObligationIds = mappings.stream()
                    .filter(m -> m.getMappingConfidence() != null && m.getMappingConfidence() >= 50)
                    .map(Mapping::getObligationId)
                    .collect(Collectors.toSet());

            List<Obligation> uncovered = obligations.stream()
                    .filter(o -> !coveredObligationIds.contains(o.getId()))
                    .toList();

            log.info("GapAnalyzeStage: scoring {} gaps in parallel for session {}", uncovered.size(), ctx.getSessionId());
            List<CompletableFuture<Gap>> futures = new ArrayList<>(uncovered.size());
            for (Obligation obl : uncovered) {
                futures.add(CompletableFuture.supplyAsync(() -> scoreGap(obl, ctx.getSessionId()), pipelineExecutor));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            for (CompletableFuture<Gap> f : futures) {
                Gap gap = f.join();
                gapRepository.save(gap);
                ctx.getGaps().add(gap);
                ctx.getSseEmitterService().send(ctx.getSessionId(), "gap.identified",
                        GapMapper.toDto(gap));
            }

            log.info("GapAnalyzeStage: {} gaps for session {}", ctx.getGaps().size(), ctx.getSessionId());
        });
    }

    private Gap scoreGap(Obligation obl, String sessionId) {
        GapScore s = gapScorer.score(
                new MatchableObligation(obl.getId(), obl.getSubject(), obl.getAction(),
                        obl.getRiskCategory(), obl.getRegulatoryPenaltyRange()),
                BedrockModel.SONNET);
        Gap gap = new Gap();
        gap.setId(IdGenerator.generateGapId());
        gap.setSessionId(sessionId);
        gap.setObligationId(obl.getId());
        gap.setGapType(GapType.control_missing);
        gap.setGapStatus(GapStatus.gap);
        gap.setNarrative(s.narrative());
        gap.setEscalationRequired(s.escalationRequired());
        gap.setSeverity(s.severity());
        gap.setLikelihood(s.likelihood());
        gap.setDetectability(s.detectability());
        gap.setBlastRadius(s.blastRadius());
        gap.setRecoverability(s.recoverability());
        gap.setResidualRisk(s.residualRisk());
        gap.setSeverityDimensions(s.severityDimensions());
        gap.setRecommendedActions(s.recommendedActions());
        return gap;
    }
}
