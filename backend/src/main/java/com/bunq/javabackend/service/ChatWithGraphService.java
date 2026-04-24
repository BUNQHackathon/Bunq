package com.bunq.javabackend.service;

import com.bunq.javabackend.dto.request.ChatWithGraphRequestDTO;
import com.bunq.javabackend.dto.response.ChatGraphEdgeDTO;
import com.bunq.javabackend.dto.response.ChatGraphNodeDTO;
import com.bunq.javabackend.model.enums.BedrockModel;
import com.bunq.javabackend.model.enums.KbType;
import com.bunq.javabackend.service.KnowledgeBaseService.RetrievedChunk;
import com.bunq.javabackend.service.bedrock.GapScore;
import com.bunq.javabackend.service.bedrock.GapScorer;
import com.bunq.javabackend.service.bedrock.MatchResult;
import com.bunq.javabackend.service.bedrock.MatchableControl;
import com.bunq.javabackend.service.bedrock.MatchableObligation;
import com.bunq.javabackend.service.bedrock.ObligationControlMatcher;
import com.bunq.javabackend.service.pipeline.prompts.SystemPrompts;
import com.bunq.javabackend.service.sse.SseEmitterService;
import com.bunq.javabackend.util.JurisdictionInference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Slf4j
@Service
public class ChatWithGraphService {

    private final KnowledgeBaseService knowledgeBaseService;
    private final ObligationControlMatcher matcher;
    private final GapScorer gapScorer;
    private final BedrockStreamingService bedrockStreamingService;
    private final SseEmitterService sseEmitterService;
    private final Executor pipelineExecutor;

    public ChatWithGraphService(
            KnowledgeBaseService knowledgeBaseService,
            ObligationControlMatcher matcher,
            GapScorer gapScorer,
            BedrockStreamingService bedrockStreamingService,
            SseEmitterService sseEmitterService,
            @Qualifier("pipelineExecutor") Executor pipelineExecutor) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.matcher = matcher;
        this.gapScorer = gapScorer;
        this.bedrockStreamingService = bedrockStreamingService;
        this.sseEmitterService = sseEmitterService;
        this.pipelineExecutor = pipelineExecutor;
    }

    public SseEmitter startChat(ChatWithGraphRequestDTO req) {
        String chatId = UUID.randomUUID().toString();
        SseEmitter emitter = sseEmitterService.register(chatId, 90_000L);
        pipelineExecutor.execute(() -> runChatWithGraph(chatId, req));
        return emitter;
    }

    private void runChatWithGraph(String chatId, ChatWithGraphRequestDTO req) {
        try {
            // Step 1: Jurisdictions
            Set<String> js;
            String hint = req.getJurisdictionHint();
            if (hint != null && !hint.isBlank()) {
                js = Set.of(hint.toUpperCase());
            } else {
                js = JurisdictionInference.inferFromText(req.getQuestion());
            }
            if (js.isEmpty()) {
                js = Set.of("NL", "DE", "FR", "UK", "US", "IE");
            }

            // Step 2: RAG retrieval
            List<RetrievedChunk> allChunks = knowledgeBaseService
                    .retrieveAllWithFilter(req.getQuestion(), 8, 16, List.copyOf(js))
                    .join();

            List<RetrievedChunk> obligationChunks = new ArrayList<>();
            List<RetrievedChunk> controlChunks = new ArrayList<>();
            List<RetrievedChunk> policyChunks = new ArrayList<>();

            for (RetrievedChunk chunk : allChunks) {
                if (chunk.kbType() == KbType.REGULATIONS) {
                    obligationChunks.add(chunk);
                } else if (chunk.kbType() == KbType.CONTROLS) {
                    controlChunks.add(chunk);
                } else if (chunk.kbType() == KbType.POLICIES) {
                    policyChunks.add(chunk);
                }
            }

            // Cap to top-5 each (already sorted by score desc from retrieval)
            List<RetrievedChunk> topObligations = obligationChunks.stream().limit(5).toList();
            List<RetrievedChunk> topControls = controlChunks.stream().limit(5).toList();

            // Step 3: Adapt to Matchable records
            List<MatchableObligation> matchableObligations = topObligations.stream()
                    .map(c -> new MatchableObligation(
                            c.chunkId(),
                            firstSentence(c.text()),
                            secondSentence(c.text()),
                            null,
                            null))
                    .toList();

            List<MatchableControl> matchableControls = topControls.stream()
                    .map(c -> new MatchableControl(
                            c.chunkId(),
                            c.text() != null && c.text().length() > 500 ? c.text().substring(0, 500) : c.text(),
                            null,
                            List.of()))
                    .toList();

            // Step 4: Emit graph_node for each obligation and control
            for (RetrievedChunk chunk : topObligations) {
                String subject = firstSentence(chunk.text());
                Map<String, Object> meta = new HashMap<>();
                meta.put("kb", "regulations");
                meta.put("score", chunk.score());
                meta.put("s3Uri", chunk.s3Uri());
                meta.put("text", chunk.text() != null
                        ? chunk.text().substring(0, Math.min(500, chunk.text().length()))
                        : "");
                String label = subject.length() > 80 ? subject.substring(0, 80) : subject;
                sseEmitterService.send(chatId, "graph_node",
                        new ChatGraphNodeDTO(chunk.chunkId(), "obligation", label, meta));
            }

            for (RetrievedChunk chunk : topControls) {
                String desc = chunk.text() != null
                        ? chunk.text().substring(0, Math.min(80, chunk.text().length()))
                        : "";
                Map<String, Object> meta = new HashMap<>();
                meta.put("kb", "controls");
                meta.put("score", chunk.score());
                meta.put("s3Uri", chunk.s3Uri());
                meta.put("text", chunk.text() != null
                        ? chunk.text().substring(0, Math.min(500, chunk.text().length()))
                        : "");
                sseEmitterService.send(chatId, "graph_node",
                        new ChatGraphNodeDTO(chunk.chunkId(), "control", desc, meta));
            }

            // Step 5: Matching fan-out
            Set<String> coveredObligationIds = new HashSet<>();
            for (MatchableObligation obl : matchableObligations) {
                List<MatchResult> results = matcher.match(obl, matchableControls);
                boolean hasSatisfactoryMatch = false;
                for (MatchResult result : results) {
                    if (result.confidence() >= 30) {
                        double confNorm = result.confidence() / 100.0;
                        sseEmitterService.send(chatId, "graph_edge",
                                new ChatGraphEdgeDTO(obl.id(), result.controlId(), "maps_to", confNorm));
                        if (result.confidence() >= 50) {
                            hasSatisfactoryMatch = true;
                        }
                    }
                }
                if (hasSatisfactoryMatch) {
                    coveredObligationIds.add(obl.id());
                }
            }

            // Step 6: Gap scoring for top-3 uncovered obligations
            List<MatchableObligation> uncovered = matchableObligations.stream()
                    .filter(obl -> !coveredObligationIds.contains(obl.id()))
                    .limit(3)
                    .toList();

            List<CompletableFuture<GapScore>> gapFutures = uncovered.stream()
                    .map(obl -> CompletableFuture.supplyAsync(
                            () -> gapScorer.score(obl, BedrockModel.SONNET), pipelineExecutor))
                    .toList();

            CompletableFuture.allOf(gapFutures.toArray(new CompletableFuture[0])).join();

            for (int i = 0; i < uncovered.size(); i++) {
                MatchableObligation obl = uncovered.get(i);
                GapScore gap = gapFutures.get(i).join();
                String oblIdShort = obl.id().length() > 8 ? obl.id().substring(0, 8) : obl.id();
                String gapId = "GAP-" + oblIdShort;

                String narrative = gap.narrative() != null ? gap.narrative() : "";
                String gapLabel = narrative.length() > 40 ? narrative.substring(0, 40) : narrative;

                String firstAction = null;
                if (gap.recommendedActions() != null && !gap.recommendedActions().isEmpty()) {
                    firstAction = gap.recommendedActions().get(0).getAction();
                }

                Map<String, Object> gapMeta = new HashMap<>();
                gapMeta.put("severity", gap.severity());
                gapMeta.put("residualRisk", gap.residualRisk());
                gapMeta.put("escalationRequired", gap.escalationRequired());
                gapMeta.put("recommendedActions", firstAction);

                sseEmitterService.send(chatId, "graph_node",
                        new ChatGraphNodeDTO(gapId, "gap", gapLabel, gapMeta));
                sseEmitterService.send(chatId, "graph_edge",
                        new ChatGraphEdgeDTO(obl.id(), gapId, "has_gap", null));
            }

            // Step 7: Stream LLM answer
            String userContent = buildUserContent(topObligations, topControls, uncovered,
                    gapFutures.stream().map(CompletableFuture::join).toList(),
                    policyChunks, req.getQuestion());

            bedrockStreamingService
                    .streamWithCachedSystem(
                            BedrockModel.HAIKU.getModelId(),
                            SystemPrompts.SYSTEM_CHAT_WITH_GRAPH,
                            userContent)
                    .doOnNext(delta -> {
                        if (delta.text() != null) {
                            sseEmitterService.send(chatId, "chat_delta", Map.of("text", delta.text()));
                        }
                    })
                    .blockLast();

            // Step 8: done
            sseEmitterService.send(chatId, "done", Map.of("chatId", chatId));
            sseEmitterService.complete(chatId);

        } catch (Exception ex) {
            log.warn("ChatWithGraph {} failed: {}", chatId, ex.getMessage(), ex);
            sseEmitterService.send(chatId, "error", Map.of("message", ex.getMessage() != null ? ex.getMessage() : "Unknown error"));
            sseEmitterService.complete(chatId);
        }
    }

    private String buildUserContent(
            List<RetrievedChunk> obligations,
            List<RetrievedChunk> controls,
            List<MatchableObligation> uncovered,
            List<GapScore> gaps,
            List<RetrievedChunk> policies,
            String question) {

        StringBuilder sb = new StringBuilder("<context>\n");

        for (RetrievedChunk c : obligations) {
            sb.append("<obligation id=\"").append(c.chunkId()).append("\">\n")
              .append(c.text() != null ? c.text() : "").append("\n</obligation>\n");
        }

        for (RetrievedChunk c : controls) {
            sb.append("<control id=\"").append(c.chunkId()).append("\">\n")
              .append(c.text() != null ? c.text() : "").append("\n</control>\n");
        }

        for (RetrievedChunk c : policies) {
            sb.append("<policy id=\"").append(c.chunkId()).append("\">\n")
              .append(c.text() != null ? c.text() : "").append("\n</policy>\n");
        }

        for (int i = 0; i < uncovered.size(); i++) {
            MatchableObligation obl = uncovered.get(i);
            GapScore gap = gaps.get(i);
            String oblIdShort = obl.id().length() > 8 ? obl.id().substring(0, 8) : obl.id();
            String gapId = "GAP-" + oblIdShort;
            sb.append("<gap id=\"").append(gapId).append("\">\n")
              .append(gap.narrative() != null ? gap.narrative() : "")
              .append("\n</gap>\n");
        }

        sb.append("</context>\n\nQuestion: ").append(question);
        return sb.toString();
    }

    private String firstSentence(String text) {
        if (text == null || text.isBlank()) return "";
        String trimmed = text.trim();
        int idx = trimmed.indexOf(". ");
        if (idx >= 0 && idx < 120) {
            return trimmed.substring(0, idx + 1).trim();
        }
        return trimmed.length() > 120 ? trimmed.substring(0, 120) : trimmed;
    }

    private String secondSentence(String text) {
        if (text == null || text.isBlank()) return "";
        String trimmed = text.trim();
        int idx = trimmed.indexOf(". ");
        if (idx < 0) return "";
        String rest = trimmed.substring(idx + 2).trim();
        int idx2 = rest.indexOf(". ");
        if (idx2 >= 0) return rest.substring(0, idx2 + 1).trim();
        return rest.length() > 120 ? rest.substring(0, 120) : rest;
    }
}
