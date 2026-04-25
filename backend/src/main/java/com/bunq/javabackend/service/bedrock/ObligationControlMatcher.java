package com.bunq.javabackend.service.bedrock;

import com.bunq.javabackend.model.enums.BedrockModel;
import com.bunq.javabackend.service.BedrockService;
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
public class ObligationControlMatcher {

    private final BedrockService bedrockService;

    public List<MatchResult> match(MatchableObligation obl, List<MatchableControl> candidates) {
        List<MatchResult> results = new ArrayList<>();
        try {
            HashMap<String, Object> userInput = new HashMap<>();
            userInput.put("obligation_id", obl.id());
            userInput.put("obligation_subject", obl.subject());
            userInput.put("obligation_action", obl.action());
            userInput.put("obligation_risk_category", obl.riskCategory());
            userInput.put("candidate_controls", candidates.stream().map(c -> {
                HashMap<String, Object> m = new HashMap<>();
                m.put("control_id", c.id());
                m.put("description", c.description());
                m.put("category", c.category());
                m.put("mapped_standards", c.mappedStandards());
                return m;
            }).toList());

            JsonNode toolInput = bedrockService.invokeModelWithTool(
                    BedrockModel.HAIKU.getModelId(),
                    SystemPrompts.MATCH_OBLIGATIONS_TO_CONTROLS,
                    userInput,
                    ToolDefinitions.MATCH_OBLIGATION_TO_CONTROLS_TOOL
            );

            JsonNode matchesNode = toolInput.isArray() ? toolInput : toolInput.path("matches");
            if (matchesNode.isArray()) {
                for (JsonNode node : matchesNode) {
                    String controlId = node.path("control_id").asText(null);
                    double confidence = node.path("match_score").asDouble(0.0);
                    String reason = node.path("reason").asText(null);
                    String mappingType = node.path("mapping_type").asText("partial");
                    results.add(new MatchResult(controlId, confidence, reason, mappingType));
                }
            }
        } catch (Exception e) {
            log.warn("Semantic match failed for obligation {}: {}", obl.id(), e.getMessage());
        }
        return results;
    }
}
