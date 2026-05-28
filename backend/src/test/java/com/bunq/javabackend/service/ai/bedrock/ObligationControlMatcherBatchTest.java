package com.bunq.javabackend.service.ai.bedrock;

import com.bunq.javabackend.model.enums.BedrockModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.ArrayNode;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ObligationControlMatcherBatchTest {

    @Mock
    private BedrockService bedrockService;

    private ObligationControlMatcher matcher;
    private final ObjectMapper om = new ObjectMapper();

    @BeforeEach
    void setUp() {
        matcher = new ObligationControlMatcher(bedrockService, om);
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    /**
     * Builds the phase-1 response JSON that BedrockService.invokeModel returns:
     * {"content":[{"text":"some analysis"}]}
     */
    private JsonNode phase1Response(String text) {
        ObjectNode root = om.createObjectNode();
        ArrayNode content = root.putArray("content");
        content.addObject().put("text", text);
        return root;
    }

    /**
     * Builds the phase-2 tool response for matchBatch:
     * {"obligations":[{"obligation_id":"obl-1","matches":[{"control_id":"c1","match_score":0.85,"reason":"r","mapping_type":"full"}]}]}
     */
    private JsonNode batchToolResponse(String oblId, String controlId, double score, String reason, String mappingType) {
        ObjectNode root = om.createObjectNode();
        ArrayNode obligations = root.putArray("obligations");
        ObjectNode oblNode = obligations.addObject();
        oblNode.put("obligation_id", oblId);
        ArrayNode matches = oblNode.putArray("matches");
        ObjectNode match = matches.addObject();
        match.put("control_id", controlId);
        match.put("match_score", score);
        match.put("reason", reason);
        match.put("mapping_type", mappingType);
        return root;
    }

    // ── tests ──────────────────────────────────────────────────────────────────

    @Test
    void matchBatch_parsesValidResponseIntoMap() {
        MatchableObligation obl = new MatchableObligation("obl-1", "subject", "action", "aml", null);
        MatchableControl ctrl = new MatchableControl("ctrl-1", "description", "aml", List.of("PSD2"));

        when(bedrockService.invokeModel(any(), any(), any(), any()))
                .thenReturn(phase1Response("analysis text"));
        when(bedrockService.invokeModelWithTool(any(), any(), any(), any(), any(), any()))
                .thenReturn(batchToolResponse("obl-1", "ctrl-1", 0.85, "strong match", "full"));

        Map<String, List<MatchResult>> result = matcher.matchBatch(
                "session-test", "map_stage",
                List.of(obl),
                Map.of("obl-1", List.of(ctrl)),
                BedrockModel.SONNET);

        assertNotNull(result);
        assertTrue(result.containsKey("obl-1"));
        List<MatchResult> matches = result.get("obl-1");
        assertEquals(1, matches.size());
        MatchResult mr = matches.get(0);
        assertEquals("ctrl-1", mr.controlId());
        assertEquals(0.85, mr.confidence(), 0.001);
        assertEquals("strong match", mr.reason());
        assertEquals("full", mr.mappingType());
    }

    @Test
    void matchBatch_phase1Fails_phase2StillRunsAndReturnsResult() {
        MatchableObligation obl = new MatchableObligation("obl-2", "subject", "action", "kyc", null);
        MatchableControl ctrl = new MatchableControl("ctrl-2", "desc", "kyc", List.of());

        // Phase 1 throws; phase 2 should still run with prior_analysis=""
        when(bedrockService.invokeModel(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("Bedrock phase-1 error"));
        when(bedrockService.invokeModelWithTool(any(), any(), any(), any(), any(), any()))
                .thenReturn(batchToolResponse("obl-2", "ctrl-2", 0.7, "partial reason", "partial"));

        Map<String, List<MatchResult>> result = matcher.matchBatch(
                "session-test", "map_stage",
                List.of(obl),
                Map.of("obl-2", List.of(ctrl)),
                BedrockModel.SONNET);

        assertNotNull(result);
        assertTrue(result.containsKey("obl-2"), "Phase-2 result must be present despite phase-1 failure");
        assertEquals("ctrl-2", result.get("obl-2").get(0).controlId());
    }

    @Test
    void matchBatch_missingObligationId_notInResultMap() {
        MatchableObligation obl1 = new MatchableObligation("obl-present", "s", "a", "cat", null);
        MatchableObligation obl2 = new MatchableObligation("obl-missing", "s2", "a2", "cat2", null);

        when(bedrockService.invokeModel(any(), any(), any(), any()))
                .thenReturn(phase1Response("analysis"));

        // Phase-2 only returns obl-present, obl-missing is absent
        when(bedrockService.invokeModelWithTool(any(), any(), any(), any(), any(), any()))
                .thenReturn(batchToolResponse("obl-present", "ctrl-x", 0.6, "reason", "partial"));

        Map<String, List<MatchResult>> result = matcher.matchBatch(
                "session-test", "map_stage",
                List.of(obl1, obl2),
                Map.of("obl-present", List.of(), "obl-missing", List.of()),
                BedrockModel.SONNET);

        assertTrue(result.containsKey("obl-present"), "obl-present should be in result");
        assertFalse(result.containsKey("obl-missing"), "obl-missing must be absent — caller handles fallback");
    }

    @Test
    void matchBatch_emptyObligationList_returnsEmptyMap() {
        Map<String, List<MatchResult>> result = matcher.matchBatch(
                "session-test", "map_stage",
                List.of(),
                Map.of(),
                BedrockModel.SONNET);

        assertNotNull(result);
        assertTrue(result.isEmpty());
        verifyNoInteractions(bedrockService);
    }
}
