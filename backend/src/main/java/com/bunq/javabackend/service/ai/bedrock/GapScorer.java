package com.bunq.javabackend.service.ai.bedrock;

import com.bunq.javabackend.model.enums.BedrockModel;
import com.bunq.javabackend.model.gap.RecommendedAction;
import com.bunq.javabackend.model.gap.SeverityDimensions;
import com.bunq.javabackend.service.ai.bedrock.BedrockService;
import com.bunq.javabackend.service.pipeline.prompts.SystemPrompts;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class GapScorer {

    private final BedrockService bedrockService;

    public GapScore score(MatchableObligation obl, BedrockModel model) {
        try {
            HashMap<String, Object> userInput = new HashMap<>();
            userInput.put("obligation_id", obl.id());
            userInput.put("obligation_subject", obl.subject());
            userInput.put("obligation_action", obl.action());
            userInput.put("risk_category", obl.riskCategory());
            userInput.put("regulatory_penalty", obl.regulatoryPenalty());

            JsonNode toolInput = bedrockService.invokeModelWithTool(
                    model.getModelId(),
                    SystemPrompts.SCORE_GAP,
                    userInput,
                    ToolDefinitions.SCORE_GAP_TOOL
            );

            return buildGapScore(toolInput);
        } catch (Exception e) {
            log.warn("Gap scoring failed for obligation {}: {}", obl.id(), e.getMessage());
            return defaultGapScore();
        }
    }

    private GapScore buildGapScore(JsonNode toolInput) {
        String narrative = toolInput.path("narrative").asText(null);
        boolean escalationRequired = toolInput.path("escalation_required").asBoolean(false);

        SeverityDimensions dims = null;
        JsonNode dimsNode = toolInput.path("severity_dimensions");
        if (!dimsNode.isMissingNode()) {
            dims = new SeverityDimensions();
            dims.setRegulatoryUrgency(dimsNode.path("regulatory_urgency").asDouble(0.0));
            dims.setPenaltySeverity(dimsNode.path("penalty_severity").asDouble(0.0));
            dims.setProbability(dimsNode.path("probability").asDouble(0.0));
            dims.setBusinessImpact(dimsNode.path("business_impact").asDouble(0.0));
            double combined = (dims.getRegulatoryUrgency() + dims.getPenaltySeverity()
                    + dims.getProbability() + dims.getBusinessImpact()) / 4.0;
            dims.setCombinedRiskScore(combined);
        }

        Double severity      = nullableDouble(toolInput, "severity");
        Double likelihood    = nullableDouble(toolInput, "likelihood");
        Double detectability = nullableDouble(toolInput, "detectability");
        Double blastRadius   = nullableDouble(toolInput, "blast_radius");
        Double recoverability= nullableDouble(toolInput, "recoverability");
        double residualRisk  = 0.4 * nz(severity) + 0.25 * nz(likelihood)
                + 0.15 * nz(detectability) + 0.10 * nz(blastRadius) + 0.10 * nz(recoverability);

        List<RecommendedAction> actions = new ArrayList<>();
        JsonNode actionsNode = toolInput.path("recommended_actions");
        if (actionsNode.isArray()) {
            for (JsonNode a : actionsNode) {
                RecommendedAction action = new RecommendedAction();
                action.setAction(a.path("action").asText(null));
                action.setSuggestedOwner(a.path("suggested_owner").asText(null));
                actions.add(action);
            }
        }

        return new GapScore(narrative, escalationRequired, severity, likelihood, detectability,
                blastRadius, recoverability, residualRisk, dims, actions);
    }

    private GapScore defaultGapScore() {
        return new GapScore(null, false, null, null, null, null, null, 0.0, null, List.of());
    }

    private static Double nullableDouble(JsonNode node, String field) {
        JsonNode n = node.path(field);
        return n.isMissingNode() || n.isNull() ? null : n.asDouble();
    }

    private static double nz(Double v) {
        return v != null ? v : 0.0;
    }
}
