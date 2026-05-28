package com.bunq.javabackend.service.pipeline.stage;

import com.bunq.javabackend.model.obligation.Obligation;
import com.bunq.javabackend.repository.ObligationRepository;
import com.bunq.javabackend.service.ai.bedrock.BedrockService;
import com.bunq.javabackend.service.infra.sse.SseEmitterService;
import com.bunq.javabackend.service.pipeline.PipelineContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FilterObligationsStageTest {

    @Mock
    private BedrockService bedrockService;
    @Mock
    private ObligationRepository obligationRepository;
    @Mock
    private SseEmitterService sseEmitterService;

    // Synchronous executor so futures complete in-thread during tests
    private final Executor syncExecutor = Runnable::run;

    private FilterObligationsStage stage;
    private final ObjectMapper om = new ObjectMapper();

    @BeforeEach
    void setUp() {
        stage = new FilterObligationsStage(bedrockService, obligationRepository, syncExecutor);
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private PipelineContext ctxWithBrief(String brief, List<Obligation> obligations) {
        PipelineContext ctx = new PipelineContext("session-1", null, null, List.of(), brief, sseEmitterService);
        ctx.getObligations().addAll(obligations);
        return ctx;
    }

    private Obligation obligation(String id) {
        Obligation o = new Obligation();
        o.setId(id);
        o.setSubject("subject-" + id);
        o.setAction("action-" + id);
        return o;
    }

    private JsonNode scoreNode(double score) {
        ObjectNode n = om.createObjectNode();
        n.put("relevance_score", score);
        n.put("relevance_reason", "test-reason");
        return n;
    }

    // ── tests ──────────────────────────────────────────────────────────────────

    @Test
    void blankBrief_skipsBedrockEntirely() {
        PipelineContext ctx = ctxWithBrief("   ", List.of(obligation("o1")));

        stage.execute(ctx).join();

        verify(bedrockService, never()).invokeModelWithTool(any(), any(), any(), any(), any(), any());
    }

    @Test
    void nullBrief_skipsBedrockEntirely() {
        PipelineContext ctx = ctxWithBrief(null, List.of(obligation("o1")));

        stage.execute(ctx).join();

        verify(bedrockService, never()).invokeModelWithTool(any(), any(), any(), any(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void scoring_keepsObligationsAboveThresholdAndDropsBelow() {
        Obligation low = obligation("low");   // score 0.1 → drop
        Obligation mid = obligation("mid");   // score 0.5 → keep
        Obligation high = obligation("high"); // score 0.9 → keep
        PipelineContext ctx = ctxWithBrief("valid brief", List.of(low, mid, high));

        // Use thenAnswer to dispatch by the "subject" key in the userInput map
        when(bedrockService.invokeModelWithTool(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    Map<String, Object> input = invocation.getArgument(4);
                    String subject = String.valueOf(input.get("subject"));
                    return switch (subject) {
                        case "subject-low"  -> scoreNode(0.1);
                        case "subject-mid"  -> scoreNode(0.5);
                        case "subject-high" -> scoreNode(0.9);
                        default -> scoreNode(1.0);
                    };
                });

        stage.execute(ctx).join();

        List<Obligation> remaining = ctx.getObligations();
        assertEquals(2, remaining.size());
        assertTrue(remaining.stream().anyMatch(o -> "mid".equals(o.getId())));
        assertTrue(remaining.stream().anyMatch(o -> "high".equals(o.getId())));
        assertFalse(remaining.stream().anyMatch(o -> "low".equals(o.getId())));
    }

    @Test
    @SuppressWarnings("unchecked")
    void scoring_sseEventSentWithCorrectCounts() {
        Obligation keep = obligation("keep");  // score 0.5 → kept
        Obligation drop = obligation("drop");  // score 0.1 → dropped
        PipelineContext ctx = ctxWithBrief("valid brief", List.of(keep, drop));

        when(bedrockService.invokeModelWithTool(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    Map<String, Object> input = invocation.getArgument(4);
                    String subject = String.valueOf(input.get("subject"));
                    return "subject-keep".equals(subject) ? scoreNode(0.5) : scoreNode(0.1);
                });

        stage.execute(ctx).join();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass((Class) Map.class);
        verify(sseEmitterService).send(eq("session-1"), eq("obligations.filtered"), payloadCaptor.capture());

        Map<String, Object> payload = payloadCaptor.getValue();
        assertEquals(2, payload.get("total"));
        assertEquals(1, payload.get("relevant"));
        assertEquals(1, payload.get("dropped"));
    }

    @Test
    void bedrockException_obligationKeptWithScore1() {
        Obligation obl = obligation("errored");
        PipelineContext ctx = ctxWithBrief("valid brief", List.of(obl));

        when(bedrockService.invokeModelWithTool(any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("Bedrock unavailable"));

        stage.execute(ctx).join();

        List<Obligation> remaining = ctx.getObligations();
        assertEquals(1, remaining.size(), "Errored obligation must NOT be dropped");
        assertEquals("errored", remaining.get(0).getId());
        assertEquals(1.0, remaining.get(0).getRelevanceScore(), 0.001);
    }

    @Test
    void noDropped_noSseEventSent() {
        Obligation obl = obligation("high");
        PipelineContext ctx = ctxWithBrief("valid brief", List.of(obl));

        when(bedrockService.invokeModelWithTool(any(), any(), any(), any(), any(), any()))
                .thenReturn(scoreNode(0.9));

        stage.execute(ctx).join();

        verify(sseEmitterService, never()).send(any(), eq("obligations.filtered"), any());
    }

    @Test
    void nullScore_obligationKept() {
        // If result node has no relevance_score field, asDouble(1.0) returns 1.0 → kept.
        // Alternatively, if invokeModelWithTool returns null → code sets score 1.0.
        Obligation obl = obligation("noScore");
        PipelineContext ctx = ctxWithBrief("valid brief", List.of(obl));

        when(bedrockService.invokeModelWithTool(any(), any(), any(), any(), any(), any()))
                .thenReturn(null);

        stage.execute(ctx).join();

        assertEquals(1, ctx.getObligations().size());
    }
}
