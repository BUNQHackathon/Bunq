package com.bunq.javabackend.service.pipeline.stage;

import com.bunq.javabackend.helper.mapper.GapMapper;
import com.bunq.javabackend.model.gap.Gap;
import com.bunq.javabackend.model.mapping.Mapping;
import com.bunq.javabackend.model.obligation.Obligation;
import com.bunq.javabackend.model.gap.RecommendedAction;
import com.bunq.javabackend.model.gap.SeverityDimensions;
import com.bunq.javabackend.model.enums.BedrockModel;
import com.bunq.javabackend.model.enums.GapStatus;
import com.bunq.javabackend.model.enums.GapType;
import com.bunq.javabackend.repository.GapRepository;
import com.bunq.javabackend.repository.MappingRepository;
import com.bunq.javabackend.repository.ObligationRepository;
import com.bunq.javabackend.service.BedrockService;
import com.bunq.javabackend.service.bedrock.ToolDefinitions;
import com.bunq.javabackend.service.pipeline.PipelineContext;
import com.bunq.javabackend.service.pipeline.PipelineStage;
import com.bunq.javabackend.service.pipeline.Stage;
import com.bunq.javabackend.service.pipeline.prompts.SystemPrompts;
import com.bunq.javabackend.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class GapAnalyzeStage implements Stage {

    private final BedrockService bedrockService;
    private final GapRepository gapRepository;
    private final ObligationRepository obligationRepository;
    private final MappingRepository mappingRepository;
    private final ObjectMapper objectMapper;

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

            for (Obligation obl : uncovered) {
                Gap gap = scoreGap(obl, ctx.getSessionId());
                gapRepository.save(gap);
                ctx.getGaps().add(gap);
                ctx.getSseEmitterService().send(ctx.getSessionId(), "gap.identified",
                        GapMapper.toDto(gap));
            }

            log.info("GapAnalyzeStage: {} gaps for session {}", ctx.getGaps().size(), ctx.getSessionId());
        });
    }

    private Gap scoreGap(Obligation obl, String sessionId) {
        try {
            HashMap<String, Object> userInput = new HashMap<String, Object>();
            userInput.put("obligation_id", obl.getId());
            userInput.put("obligation_subject", obl.getSubject());
            userInput.put("obligation_action", obl.getAction());
            userInput.put("risk_category", obl.getRiskCategory());
            userInput.put("regulatory_penalty", obl.getRegulatoryPenaltyRange());

            JsonNode toolInput = bedrockService.invokeModelWithTool(
                    BedrockModel.OPUS.getModelId(),
                    SystemPrompts.SCORE_GAP,
                    userInput,
                    ToolDefinitions.SCORE_GAP_TOOL
            );

            return buildGap(toolInput, obl, sessionId);
        } catch (Exception e) {
            log.warn("Gap scoring failed for obligation {}: {}", obl.getId(), e.getMessage());
            return buildDefaultGap(obl, sessionId);
        }
    }

    private Gap buildGap(JsonNode toolInput, Obligation obl, String sessionId) {
        Gap gap = new Gap();
        gap.setId(IdGenerator.generateGapId());
        gap.setSessionId(sessionId);
        gap.setObligationId(obl.getId());
        gap.setGapType(GapType.control_missing);
        gap.setGapStatus(GapStatus.gap);
        gap.setNarrative(toolInput.path("narrative").asText(null));
        gap.setEscalationRequired(toolInput.path("escalation_required").asBoolean(false));

        JsonNode dimsNode = toolInput.path("severity_dimensions");
        if (!dimsNode.isMissingNode()) {
            SeverityDimensions dims = new SeverityDimensions();
            dims.setRegulatoryUrgency(dimsNode.path("regulatory_urgency").asDouble(0.0));
            dims.setPenaltySeverity(dimsNode.path("penalty_severity").asDouble(0.0));
            dims.setProbability(dimsNode.path("probability").asDouble(0.0));
            dims.setBusinessImpact(dimsNode.path("business_impact").asDouble(0.0));
            double combined = (dims.getRegulatoryUrgency() + dims.getPenaltySeverity()
                    + dims.getProbability() + dims.getBusinessImpact()) / 4.0;
            dims.setCombinedRiskScore(combined);
            gap.setSeverityDimensions(dims);
        }

        // 5-dimensional residual risk
        Double severity      = nullableDouble(toolInput, "severity");
        Double likelihood    = nullableDouble(toolInput, "likelihood");
        Double detectability = nullableDouble(toolInput, "detectability");
        Double blastRadius   = nullableDouble(toolInput, "blast_radius");
        Double recoverability= nullableDouble(toolInput, "recoverability");
        gap.setSeverity(severity);
        gap.setLikelihood(likelihood);
        gap.setDetectability(detectability);
        gap.setBlastRadius(blastRadius);
        gap.setRecoverability(recoverability);
        double residual = 0.4 * nz(severity) + 0.25 * nz(likelihood)
                + 0.15 * nz(detectability) + 0.10 * nz(blastRadius) + 0.10 * nz(recoverability);
        gap.setResidualRisk(residual);

        JsonNode actionsNode = toolInput.path("recommended_actions");
        if (actionsNode.isArray()) {
            List<RecommendedAction> actions = new ArrayList<>();
            for (JsonNode a : actionsNode) {
                RecommendedAction action = new RecommendedAction();
                action.setAction(a.path("action").asText(null));
                action.setSuggestedOwner(a.path("suggested_owner").asText(null));
                actions.add(action);
            }
            gap.setRecommendedActions(actions);
        }

        return gap;
    }

    private static Double nullableDouble(JsonNode node, String field) {
        JsonNode n = node.path(field);
        return n.isMissingNode() || n.isNull() ? null : n.asDouble();
    }

    private static double nz(Double v) {
        return v != null ? v : 0.0;
    }

    private Gap buildDefaultGap(Obligation obl, String sessionId) {
        Gap gap = new Gap();
        gap.setId(IdGenerator.generateGapId());
        gap.setSessionId(sessionId);
        gap.setObligationId(obl.getId());
        gap.setGapType(GapType.control_missing);
        gap.setGapStatus(GapStatus.gap);
        gap.setEscalationRequired(false);
        return gap;
    }
}
