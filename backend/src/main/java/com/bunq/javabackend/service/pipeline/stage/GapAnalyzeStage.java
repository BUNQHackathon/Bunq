package com.bunq.javabackend.service.pipeline.stage;

import com.bunq.javabackend.exception.GapScoringException;
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
import com.bunq.javabackend.service.ai.bedrock.GapScore;
import com.bunq.javabackend.service.ai.bedrock.GapScorer;
import com.bunq.javabackend.service.ai.bedrock.MatchableObligation;
import com.bunq.javabackend.service.pipeline.GapCoverage;
import com.bunq.javabackend.service.pipeline.GapCoverage.CoverageStatus;
import com.bunq.javabackend.service.pipeline.PipelineContext;
import com.bunq.javabackend.service.pipeline.PipelineStage;
import com.bunq.javabackend.service.pipeline.Stage;
import com.bunq.javabackend.util.IdGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

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
                           @Qualifier("stageWorkerExecutor") Executor pipelineExecutor) {
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

            // Best (max) mapping confidence per obligation, and which obligations have any mapping at all.
            Map<String, Double> bestConfidenceByObligation = new HashMap<>();
            Set<String> mappedObligationIds = new HashSet<>();
            for (Mapping m : mappings) {
                String oblId = m.getObligationId();
                if (oblId == null) {
                    continue;
                }
                mappedObligationIds.add(oblId);
                if (m.getMappingConfidence() != null) {
                    bestConfidenceByObligation.merge(oblId, m.getMappingConfidence(), Math::max);
                }
            }

            // TODO: no degraded-retrieval signal is plumbed into this stage yet; hardcoded false.
            List<Obligation> uncovered = obligations.stream()
                    .filter(o -> GapCoverage.classify(bestConfidenceByObligation.get(o.getId()),
                            mappedObligationIds.contains(o.getId()), false) != CoverageStatus.SATISFIED)
                    .toList();

            log.info("GapAnalyzeStage: scoring {} gaps in parallel for session {}", uncovered.size(), ctx.getSessionId());
            List<CompletableFuture<Gap>> futures = new ArrayList<>(uncovered.size());
            for (Obligation obl : uncovered) {
                futures.add(CompletableFuture.supplyAsync(() -> scoreGap(obl, ctx.getSessionId()), pipelineExecutor));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            Map<CoverageStatus, Integer> statusCounts = new EnumMap<>(CoverageStatus.class);
            for (CompletableFuture<Gap> f : futures) {
                Gap gap = f.join();
                CoverageStatus status = GapCoverage.classify(bestConfidenceByObligation.get(gap.getObligationId()),
                        mappedObligationIds.contains(gap.getObligationId()), false);
                statusCounts.merge(status, 1, Integer::sum);
                switch (status) {
                    case SUBSTANTIALLY_COVERED, PARTIAL, JURISDICTION_DELTA, NEEDS_REVIEW -> {
                        gap.setGapType(GapType.control_weak);
                        gap.setGapStatus(GapStatus.partial);
                    }
                    case CONTROL_MISSING -> {
                        gap.setGapType(GapType.control_missing);
                        gap.setGapStatus(GapStatus.gap);
                    }
                    case SATISFIED -> {
                        // unreachable: SATISFIED obligations never produce a gap
                    }
                }
                Map<String, String> metadata = gap.getMetadata();
                if (metadata == null) {
                    metadata = new HashMap<>();
                }
                metadata.put("gap_semantics", status.name().toLowerCase());
                metadata.put("best_mapping_confidence",
                        String.valueOf(bestConfidenceByObligation.getOrDefault(gap.getObligationId(), 0.0)));
                gap.setMetadata(metadata);

                gapRepository.save(gap);
                ctx.getGaps().add(gap);
                ctx.getSseEmitterService().send(ctx.getSessionId(), "gap.identified",
                        GapMapper.toDto(gap));
            }

            log.info("GapAnalyzeStage: {} gaps for session {} — {}", ctx.getGaps().size(), ctx.getSessionId(), statusCounts);
        }, pipelineExecutor);
    }

    private Gap scoreGap(Obligation obl, String sessionId) {
        Gap gap = new Gap();
        gap.setId(IdGenerator.generateGapId());
        gap.setSessionId(sessionId);
        gap.setObligationId(obl.getId());
        gap.setGapType(GapType.control_missing);
        gap.setGapStatus(GapStatus.gap);
        try {
            GapScore s = gapScorer.score(
                    sessionId, "score_gap",
                    new MatchableObligation(obl.getId(), obl.getSubject(), obl.getAction(),
                            obl.getRiskCategory(), obl.getRegulatoryPenaltyRange()),
                    BedrockModel.SONNET);
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
        } catch (GapScoringException e) {
            log.warn("GapAnalyzeStage: scoring inconclusive for obligation {}, applying conservative defaults: {}",
                    obl.getId(), e.getMessage());
            gap.setEscalationRequired(true);
            gap.setResidualRisk(0.5);
            gap.setNarrative("Gap scoring inconclusive — manual review required.");
        }
        return gap;
    }
}
