package com.bunq.javabackend.service.pipeline.stage;

import com.bunq.javabackend.model.control.Control;
import com.bunq.javabackend.model.obligation.Obligation;
import com.bunq.javabackend.repository.ControlRepository;
import com.bunq.javabackend.repository.EvidenceRepository;
import com.bunq.javabackend.repository.MappingRepository;
import com.bunq.javabackend.repository.ObligationRepository;
import com.bunq.javabackend.service.ai.bedrock.MatchResult;
import com.bunq.javabackend.service.ai.bedrock.MatchableControl;
import com.bunq.javabackend.service.ai.bedrock.MatchableObligation;
import com.bunq.javabackend.service.ai.bedrock.ObligationControlMatcher;
import com.bunq.javabackend.service.ai.kb.KnowledgeBaseService;
import com.bunq.javabackend.service.ai.kb.Reranker;
import com.bunq.javabackend.service.infra.AuditLogService;
import com.bunq.javabackend.service.infra.sse.SseEmitterService;
import com.bunq.javabackend.service.pipeline.PipelineContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MapObligationsControlsBatchFallbackTest {

    @Mock private ObligationControlMatcher matcher;
    @Mock private MappingRepository mappingRepository;
    @Mock private ObligationRepository obligationRepository;
    @Mock private ControlRepository controlRepository;
    @Mock private AuditLogService auditLogService;
    @Mock private EvidenceRepository evidenceRepository;
    @Mock private KnowledgeBaseService knowledgeBaseService;
    @Mock private Reranker reranker;
    @Mock private SseEmitterService sseEmitterService;

    private final Executor syncExecutor = Runnable::run;
    private MapObligationsControlsStage stage;

    @BeforeEach
    void setUp() {
        stage = new MapObligationsControlsStage(
                matcher, mappingRepository, obligationRepository, controlRepository,
                auditLogService, evidenceRepository, knowledgeBaseService, reranker, syncExecutor);
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private Obligation obligation(String id) {
        Obligation o = new Obligation();
        o.setId(id);
        o.setSubject("subject");
        o.setAction("action");
        o.setRiskCategory("aml");
        return o;
    }

    private Control control(String id) {
        Control c = new Control();
        c.setId(id);
        c.setDescription("description-" + id);
        return c;
    }

    /**
     * Creates a ctx with the given obligations AND a control list so that
     * prepareObligation finds uncached candidates (required for processGroup to run).
     */
    private PipelineContext ctx(List<Obligation> obligations, List<Control> controls) {
        PipelineContext ctx = new PipelineContext("session-x", null, null, List.of(), "brief", sseEmitterService);
        ctx.getObligations().addAll(obligations);
        ctx.getControls().addAll(controls);
        return ctx;
    }

    // ── tests ──────────────────────────────────────────────────────────────────

    /**
     * matchBatch returns a Map missing "obl-b" →
     * stage must call matcher.match() as single fallback for "obl-b".
     *
     * Controls are pre-loaded in ctx so prepareObligation finds uncached candidates
     * and processGroup is actually invoked.
     */
    @Test
    void missingObligationInBatchResponse_triggersMatchSingleFallback() {
        Obligation oblA = obligation("obl-a");
        Obligation oblB = obligation("obl-b");
        Control ctrl = control("ctrl-shared");
        // Provide controls in ctx so prepareObligation can build uncachedMatchable
        PipelineContext ctx = ctx(List.of(oblA, oblB), List.of(ctrl));

        when(evidenceRepository.findBySessionId(any())).thenReturn(List.of());
        // KB returns empty → structuralFilter will also be empty → falls back to allControls list
        when(knowledgeBaseService.retrieveControls(any(), anyInt()))
                .thenReturn(CompletableFuture.completedFuture(List.of()));
        // No cached mappings → ctrl goes into uncachedMatchable
        when(mappingRepository.findById(any())).thenReturn(Optional.empty());

        // matchBatch returns result only for obl-a; obl-b is absent → fallback triggered
        when(matcher.matchBatch(any(), any(), any(), any(), any()))
                .thenReturn(Map.of("obl-a", List.of(new MatchResult("ctrl-1", 0.8, "reason", "full"))));

        // Single-match fallback for obl-b returns one result
        when(matcher.match(any(), any(), any(), any(), any()))
                .thenReturn(List.of(new MatchResult("ctrl-2", 0.6, "fallback reason", "partial")));

        stage.execute(ctx).join();

        // single fallback must be called exactly once (for obl-b)
        verify(matcher, times(1)).match(any(), any(), any(), any(), any());
    }

    /**
     * When matchBatch returns results for ALL obligations, matcher.match() must not be called.
     */
    @Test
    void allObligationsInBatchResponse_noSingleFallback() {
        Obligation obl = obligation("obl-1");
        Control ctrl = control("ctrl-x");
        PipelineContext ctx = ctx(List.of(obl), List.of(ctrl));

        when(evidenceRepository.findBySessionId(any())).thenReturn(List.of());
        when(knowledgeBaseService.retrieveControls(any(), anyInt()))
                .thenReturn(CompletableFuture.completedFuture(List.of()));
        when(mappingRepository.findById(any())).thenReturn(Optional.empty());

        when(matcher.matchBatch(any(), any(), any(), any(), any()))
                .thenReturn(Map.of("obl-1", List.of(new MatchResult("ctrl-3", 0.9, "r", "full"))));

        stage.execute(ctx).join();

        verify(matcher, never()).match(any(), any(), any(), any(), any());
    }
}
