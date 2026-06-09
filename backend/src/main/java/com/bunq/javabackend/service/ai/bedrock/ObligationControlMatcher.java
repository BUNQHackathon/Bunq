package com.bunq.javabackend.service.ai.bedrock;

import com.bunq.javabackend.model.enums.BedrockModel;
import com.bunq.javabackend.service.pipeline.prompts.SystemPrompts;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ObligationControlMatcher {

    private final BedrockService bedrockService;
    private final ObjectMapper objectMapper;

    public List<MatchResult> match(String sessionId, String stage,
                                    MatchableObligation obl, List<MatchableControl> candidates,
                                    BedrockModel model) {
        List<MatchResult> results = new ArrayList<>();
        Set<String> candidateIds = candidates.stream().map(MatchableControl::id).collect(Collectors.toSet());
        try {
            // --- Phase 1: reasoning call (no tool) ---
            // Fix 12: cached system block so the stable regulation prefix hits Bedrock prompt cache.
            String analysisText = "";
            try {
                HashMap<String, Object> reasoningInput = new HashMap<>();
                reasoningInput.put("obligation_id", obl.id());
                reasoningInput.put("obligation_subject", obl.subject());
                reasoningInput.put("obligation_action", obl.action());
                reasoningInput.put("obligation_risk_category", obl.riskCategory());
                reasoningInput.put("candidate_controls", candidates.stream().map(c -> {
                    HashMap<String, Object> m = new HashMap<>();
                    m.put("control_id", c.id());
                    m.put("description", c.description());
                    m.put("category", c.category());
                    m.put("mapped_standards", c.mappedStandards());
                    return m;
                }).toList());

                String phase1RequestJson = objectMapper.writeValueAsString(Map.of(
                        "anthropic_version", "bedrock-2023-05-31",
                        "max_tokens", 1024,
                        "system", List.of(Map.of(
                                "type", "text",
                                "text", SystemPrompts.MATCH_REASONING,
                                "cache_control", Map.of("type", "ephemeral")
                        )),
                        "messages", List.of(Map.of(
                                "role", "user",
                                "content", objectMapper.writeValueAsString(reasoningInput)
                        ))
                ));

                JsonNode phase1Response = bedrockService.invokeModel(sessionId, "map_reasoning",
                        model.getModelId(), phase1RequestJson);
                JsonNode content = phase1Response.path("content");
                if (content.isArray() && !content.isEmpty()) {
                    String t = content.get(0).path("text").asString();
                    if (t != null) analysisText = t;
                }
            } catch (Exception e) {
                log.warn("Phase-1 reasoning failed for obligation {}: {}", obl.id(), e.getMessage());
                // analysisText stays "" — graceful fallback, phase 2 still runs
            }

            // --- Phase 2: structured tool call ---
            HashMap<String, Object> userInput = new HashMap<>();
            userInput.put("obligation_id", obl.id());
            userInput.put("obligation_subject", obl.subject());
            userInput.put("obligation_action", obl.action());
            userInput.put("obligation_risk_category", obl.riskCategory());
            userInput.put("prior_analysis", analysisText);
            userInput.put("candidate_controls", candidates.stream().map(c -> {
                HashMap<String, Object> m = new HashMap<>();
                m.put("control_id", c.id());
                m.put("description", c.description());
                m.put("category", c.category());
                m.put("mapped_standards", c.mappedStandards());
                return m;
            }).toList());

            JsonNode toolInput = bedrockService.invokeModelWithTool(
                    sessionId, stage,
                    model.getModelId(),
                    SystemPrompts.MATCH_OBLIGATIONS_TO_CONTROLS,
                    userInput,
                    ToolDefinitions.MATCH_OBLIGATION_TO_CONTROLS_TOOL
            );

            // Fix 3: filter hallucinated control IDs against the candidate list.
            JsonNode matchesNode = toolInput.isArray() ? toolInput : toolInput.path("matches");
            if (matchesNode.isArray()) {
                for (JsonNode node : matchesNode) {
                    String controlId = node.path("control_id").asString();
                    if (controlId == null || !candidateIds.contains(controlId)) {
                        log.warn("Dropping hallucinated control_id '{}' for obligation {} (not in candidates)",
                                controlId, obl.id());
                        continue;
                    }
                    double confidence = node.path("match_score").asDouble(0.0);
                    String reason = node.path("reason").asString();
                    String mappingType = node.path("mapping_type").asString();
                    results.add(new MatchResult(controlId, confidence, reason,
                            mappingType != null ? mappingType : "partial"));
                }
            }
        } catch (Exception e) {
            log.warn("Semantic match failed for obligation {}: {}", obl.id(), e.getMessage());
        }
        return results;
    }

    /**
     * Batched two-phase match for a group of obligations.
     * Phase 1: single invokeModel reasoning call covering all obligations in the group.
     * Phase 2: single invokeModelWithTool call returning matches for all obligations.
     *
     * @return Map from obligationId to its MatchResult list. Obligations missing from
     *         the response are NOT present in the map — callers must handle them as fallback.
     */
    public Map<String, List<MatchResult>> matchBatch(
            String sessionId, String stage,
            List<MatchableObligation> obligations,
            Map<String, List<MatchableControl>> candidatesByObligationId,
            BedrockModel model) {

        if (obligations.isEmpty()) return Collections.emptyMap();

        // --- Phase 1: batch reasoning call (no tool) ---
        // Fix 12: cached system block (byte-stable prefix).
        String analysisText = "";
        try {
            List<Map<String, Object>> oblItems = new ArrayList<>();
            for (MatchableObligation obl : obligations) {
                HashMap<String, Object> item = new HashMap<>();
                item.put("obligation_id", obl.id());
                item.put("obligation_subject", obl.subject());
                item.put("obligation_action", obl.action());
                item.put("obligation_risk_category", obl.riskCategory());
                List<MatchableControl> candidates = candidatesByObligationId.getOrDefault(obl.id(), List.of());
                item.put("candidate_controls", candidates.stream().map(c -> {
                    HashMap<String, Object> m = new HashMap<>();
                    m.put("control_id", c.id());
                    m.put("description", c.description());
                    m.put("category", c.category());
                    m.put("mapped_standards", c.mappedStandards());
                    return m;
                }).toList());
                oblItems.add(item);
            }

            String phase1RequestJson = objectMapper.writeValueAsString(Map.of(
                    "anthropic_version", "bedrock-2023-05-31",
                    "max_tokens", 2048,
                    "system", List.of(Map.of(
                            "type", "text",
                            "text", SystemPrompts.MATCH_REASONING,
                            "cache_control", Map.of("type", "ephemeral")
                    )),
                    "messages", List.of(Map.of(
                            "role", "user",
                            "content", objectMapper.writeValueAsString(Map.of("obligations", oblItems))
                    ))
            ));

            JsonNode phase1Response = bedrockService.invokeModel(sessionId, "map_reasoning_batch",
                    model.getModelId(), phase1RequestJson);
            JsonNode content = phase1Response.path("content");
            if (content.isArray() && !content.isEmpty()) {
                String t = content.get(0).path("text").asString();
                if (t != null) analysisText = t;
            }
        } catch (Exception e) {
            log.warn("Phase-1 batch reasoning failed (obligations={}): {}",
                    obligations.stream().map(MatchableObligation::id).toList(), e.getMessage());
            // analysisText stays "" — graceful fallback, phase 2 still runs
        }

        // --- Phase 2: batched structured tool call ---
        Map<String, List<MatchResult>> results = new HashMap<>();
        try {
            List<Map<String, Object>> oblItems = new ArrayList<>();
            for (MatchableObligation obl : obligations) {
                HashMap<String, Object> item = new HashMap<>();
                item.put("obligation_id", obl.id());
                item.put("obligation_subject", obl.subject());
                item.put("obligation_action", obl.action());
                item.put("obligation_risk_category", obl.riskCategory());
                List<MatchableControl> candidates = candidatesByObligationId.getOrDefault(obl.id(), List.of());
                item.put("candidate_controls", candidates.stream().map(c -> {
                    HashMap<String, Object> m = new HashMap<>();
                    m.put("control_id", c.id());
                    m.put("description", c.description());
                    m.put("category", c.category());
                    m.put("mapped_standards", c.mappedStandards());
                    return m;
                }).toList());
                oblItems.add(item);
            }

            HashMap<String, Object> userInput = new HashMap<>();
            userInput.put("prior_analysis", analysisText);
            userInput.put("obligations", oblItems);

            JsonNode toolInput = bedrockService.invokeModelWithTool(
                    sessionId, stage,
                    model.getModelId(),
                    SystemPrompts.MATCH_OBLIGATIONS_TO_CONTROLS,
                    userInput,
                    ToolDefinitions.MATCH_OBLIGATIONS_BATCH_TOOL
            );

            JsonNode obligationsNode = toolInput.path("obligations");
            if (obligationsNode.isArray()) {
                for (JsonNode oblNode : obligationsNode) {
                    String obligationId = oblNode.path("obligation_id").asString();
                    if (obligationId == null) continue;
                    // Fix 3: build candidate-ID set for this obligation to filter hallucinations.
                    Set<String> candIds = candidatesByObligationId
                            .getOrDefault(obligationId, List.of())
                            .stream().map(MatchableControl::id).collect(Collectors.toSet());
                    List<MatchResult> matchResults = new ArrayList<>();
                    JsonNode matchesNode = oblNode.path("matches");
                    if (matchesNode.isArray()) {
                        for (JsonNode node : matchesNode) {
                            String controlId = node.path("control_id").asString();
                            if (controlId == null || !candIds.contains(controlId)) {
                                log.warn("Dropping hallucinated control_id '{}' for obligation {} (not in candidates)",
                                        controlId, obligationId);
                                continue;
                            }
                            double confidence = node.path("match_score").asDouble(0.0);
                            String reason = node.path("reason").asString();
                            String mappingType = node.path("mapping_type").asString();
                            matchResults.add(new MatchResult(controlId, confidence, reason,
                                    mappingType != null ? mappingType : "partial"));
                        }
                    }
                    results.put(obligationId, matchResults);
                }
            }
        } catch (Exception e) {
            log.warn("Phase-2 batch tool call failed (obligations={}): {}",
                    obligations.stream().map(MatchableObligation::id).toList(), e.getMessage());
            // Return whatever was parsed before the exception; missing obligations handled by caller fallback
        }
        return results;
    }
}
