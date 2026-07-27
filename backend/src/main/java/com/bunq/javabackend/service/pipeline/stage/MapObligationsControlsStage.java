package com.bunq.javabackend.service.pipeline.stage;

import com.bunq.javabackend.helper.mapper.MappingMapper;
import com.bunq.javabackend.model.control.Control;
import com.bunq.javabackend.model.mapping.Mapping;
import com.bunq.javabackend.model.obligation.Obligation;
import com.bunq.javabackend.model.enums.GapStatus;
import com.bunq.javabackend.model.enums.MappingType;
import com.bunq.javabackend.model.evidence.Evidence;
import com.bunq.javabackend.repository.ControlRepository;
import com.bunq.javabackend.repository.EvidenceRepository;
import com.bunq.javabackend.repository.MappingRepository;
import com.bunq.javabackend.repository.ObligationRepository;
import com.bunq.javabackend.service.infra.AuditLogService;
import com.bunq.javabackend.service.ai.kb.KnowledgeBaseService;
import com.bunq.javabackend.service.ai.kb.Reranker;
import com.bunq.javabackend.service.ai.bedrock.MatchResult;
import com.bunq.javabackend.service.ai.bedrock.MatchableControl;
import com.bunq.javabackend.service.ai.bedrock.MatchableObligation;
import com.bunq.javabackend.service.ai.bedrock.ObligationControlMatcher;
import com.bunq.javabackend.model.enums.BedrockModel;
import com.bunq.javabackend.service.pipeline.PipelineContext;
import com.bunq.javabackend.service.pipeline.PipelineStage;
import com.bunq.javabackend.service.pipeline.Stage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;

@Slf4j
@Service
public class MapObligationsControlsStage implements Stage {

    public static final int SATISFIED_CONFIDENCE_THRESHOLD = 75;

    private static final int BATCH_SIZE = 10;
    private static final int BATCH_GROUP_SIZE = 3;
    private static final int MAX_CANDIDATE_CONTROLS = 20;
    private static final int KB_TOP_K = 20;  // fetch more from KB; reranker narrows to RERANK_TOP_N (legacy path)
    private static final int RERANK_TOP_N = 20;

    /** Final candidate cap after anchor rescue is merged into the reranked top-N. */
    private static final int MAX_TOTAL_CANDIDATES = 25;

    /**
     * Critical AML terms that must never be silently dropped by reranking.
     * Terms of 3 chars or fewer (mostly acronyms) are matched as whole words only
     * (both boundaries) — a leading-boundary-only match would let "STR" match inside
     * "strategy" or "dia" match inside "Diamond". Longer phrases match on a leading
     * boundary only, so a stem like "asset freez" also catches "asset freezing".
     */
    private static final List<String> ANCHOR_LEXICON = List.of(
            "pep", "politically exposed", "sanctions", "asset freez", "terrorist list",
            "uif", "fiu", "str", "suspicious transaction", "cdd", "due diligence",
            "kyc", "edd", "beneficial owner", "ubo", "retention", "record keeping",
            "ateco", "screening", "monitoring", "senior management approval",
            "s.ar.a.", "aggregate reporting", "self-assessment"
    );

    private static final Map<String, Pattern> ANCHOR_PATTERNS = buildAnchorPatterns();

    private static Map<String, Pattern> buildAnchorPatterns() {
        Map<String, Pattern> patterns = new LinkedHashMap<>();
        for (String anchor : ANCHOR_LEXICON) {
            String quoted = Pattern.quote(anchor);
            String regex = anchor.length() <= 3 ? "\\b" + quoted + "\\b" : "\\b" + quoted;
            patterns.put(anchor, Pattern.compile(regex, Pattern.CASE_INSENSITIVE));
        }
        return patterns;
    }

    private final ObligationControlMatcher matcher;
    private final MappingRepository mappingRepository;
    private final ObligationRepository obligationRepository;
    private final ControlRepository controlRepository;
    private final AuditLogService auditLogService;
    private final EvidenceRepository evidenceRepository;
    private final KnowledgeBaseService knowledgeBaseService;
    private final Reranker reranker;
    private final Executor pipelineExecutor;

    public MapObligationsControlsStage(ObligationControlMatcher matcher, MappingRepository mappingRepository,
                                       ObligationRepository obligationRepository, ControlRepository controlRepository,
                                       AuditLogService auditLogService, EvidenceRepository evidenceRepository,
                                       KnowledgeBaseService knowledgeBaseService,
                                       Reranker reranker,
                                       @Qualifier("stageWorkerExecutor") Executor pipelineExecutor) {
        this.matcher = matcher;
        this.mappingRepository = mappingRepository;
        this.obligationRepository = obligationRepository;
        this.controlRepository = controlRepository;
        this.auditLogService = auditLogService;
        this.evidenceRepository = evidenceRepository;
        this.knowledgeBaseService = knowledgeBaseService;
        this.reranker = reranker;
        this.pipelineExecutor = pipelineExecutor;
    }

    @Override
    public PipelineStage stage() {
        return PipelineStage.MAP_OBLIGATIONS_CONTROLS;
    }

    @Override
    public CompletableFuture<Void> execute(PipelineContext ctx) {
        return CompletableFuture.runAsync(() -> {
            List<String> evidenceHashes = evidenceRepository.findBySessionId(ctx.getSessionId())
                    .stream().map(Evidence::getSha256).filter(Objects::nonNull).toList();

            List<Obligation> obligations = ctx.getObligations();
            if (obligations.isEmpty()) {
                obligations = obligationRepository.findBySessionId(ctx.getSessionId());
                ctx.getObligations().addAll(obligations);
            }

            List<Control> controls = ctx.getControls();
            if (controls.isEmpty()) {
                controls = controlRepository.findBySessionId(ctx.getSessionId());
                ctx.getControls().addAll(controls);
            }

            if (obligations.isEmpty()) {
                log.info("MapObligationsControlsStage: no obligations for session {}", ctx.getSessionId());
                return;
            }

            int processed = 0;
            int computedCount = 0;
            int reusedCount = 0;
            for (int i = 0; i < obligations.size(); i += BATCH_SIZE) {
                List<Obligation> batch = obligations.subList(i, Math.min(i + BATCH_SIZE, obligations.size()));
                BatchResult batchResult = processBatch(batch, controls, ctx, evidenceHashes);
                for (Mapping m : batchResult.mappings()) {
                    ctx.getMappings().add(m);
                    ctx.getSseEmitterService().send(ctx.getSessionId(), "mapping.computed",
                            MappingMapper.toDto(m));
                }
                computedCount += batchResult.computed();
                reusedCount += batchResult.reused();
                processed += batch.size();
                ctx.getSseEmitterService().send(ctx.getSessionId(), "mapping.progress",
                        Map.of("processed", processed, "total", obligations.size(),
                                "gapsSoFar", 0));
            }

            log.info("MapObligationsControlsStage: {} computed via Bedrock, {} reused from cache",
                    computedCount, reusedCount);
        }, pipelineExecutor);
    }

    private record BatchResult(List<Mapping> mappings, int computed, int reused) {}

    /** Holds per-obligation candidate data ready for Bedrock (uncached controls only). */
    private record ObligationCandidate(
            Obligation obligation,
            MatchableObligation matchable,
            List<MatchableControl> uncachedMatchable,
            List<Mapping> cachedMappings,
            int reusedCount,
            String candidateRoute,
            int candidateCount) {}

    private BatchResult processBatch(List<Obligation> batch, List<Control> allControls,
                                     PipelineContext ctx, List<String> evidenceHashes) {

        // Step 1: compute candidates and split cached/uncached per obligation (can parallelise)
        List<CompletableFuture<ObligationCandidate>> prepFutures = new ArrayList<>(batch.size());
        for (Obligation obl : batch) {
            prepFutures.add(CompletableFuture.supplyAsync(
                    () -> prepareObligation(obl, allControls), pipelineExecutor));
        }
        CompletableFuture.allOf(prepFutures.toArray(new CompletableFuture[0])).join();

        List<ObligationCandidate> toProcess = new ArrayList<>();
        List<Mapping> allMappings = new ArrayList<>();
        int totalReused = 0;
        Map<String, Integer> routeCounts = new LinkedHashMap<>();
        Map<String, Integer> routeCandidateTotals = new LinkedHashMap<>();

        for (CompletableFuture<ObligationCandidate> f : prepFutures) {
            ObligationCandidate oc = f.join();
            allMappings.addAll(oc.cachedMappings());
            totalReused += oc.reusedCount();
            routeCounts.merge(oc.candidateRoute(), 1, Integer::sum);
            routeCandidateTotals.merge(oc.candidateRoute(), oc.candidateCount(), Integer::sum);
            if (!oc.uncachedMatchable().isEmpty()) {
                toProcess.add(oc);
            }
        }
        log.info("MapObligationsControlsStage: candidate routes for batch of {} obligations = {} (total candidates per route = {})",
                batch.size(), routeCounts, routeCandidateTotals);

        if (toProcess.isEmpty()) {
            return new BatchResult(allMappings, 0, totalReused);
        }

        // Step 2: group toProcess into groups of BATCH_GROUP_SIZE; dispatch each group in parallel
        List<List<ObligationCandidate>> groups = new ArrayList<>();
        for (int i = 0; i < toProcess.size(); i += BATCH_GROUP_SIZE) {
            groups.add(toProcess.subList(i, Math.min(i + BATCH_GROUP_SIZE, toProcess.size())));
        }

        List<CompletableFuture<GroupResult>> groupFutures = new ArrayList<>(groups.size());
        for (List<ObligationCandidate> group : groups) {
            groupFutures.add(CompletableFuture.supplyAsync(
                    () -> processGroup(group, ctx, evidenceHashes), pipelineExecutor));
        }
        CompletableFuture.allOf(groupFutures.toArray(new CompletableFuture[0])).join();

        int totalComputed = 0;
        for (CompletableFuture<GroupResult> f : groupFutures) {
            GroupResult gr = f.join();
            allMappings.addAll(gr.mappings());
            totalComputed += gr.computed();
        }

        return new BatchResult(allMappings, totalComputed, totalReused);
    }

    private record GroupResult(List<Mapping> mappings, int computed) {}

    /** Phase-1+2 batched call for a group; fallback to single-call for any missing obligation_id. */
    private GroupResult processGroup(List<ObligationCandidate> group,
                                     PipelineContext ctx, List<String> evidenceHashes) {
        List<MatchableObligation> matchableObls = group.stream()
                .map(ObligationCandidate::matchable).toList();
        Map<String, List<MatchableControl>> candidatesById = new HashMap<>();
        for (ObligationCandidate oc : group) {
            candidatesById.put(oc.obligation().getId(), oc.uncachedMatchable());
        }

        Map<String, List<MatchResult>> batchResults;
        try {
            batchResults = matcher.matchBatch(
                    ctx.getSessionId(), "map_obligations_controls",
                    matchableObls, candidatesById, BedrockModel.HAIKU);
        } catch (Exception e) {
            log.warn("matchBatch failed for group of {} obligations, falling back to single calls: {}",
                    group.size(), e.getMessage());
            batchResults = new HashMap<>();
        }

        List<Mapping> mappings = new ArrayList<>();
        int computed = 0;

        for (ObligationCandidate oc : group) {
            String oblId = oc.obligation().getId();
            List<MatchResult> matchResults = batchResults.get(oblId);

            if (matchResults == null) {
                // Fallback: obligation_id missing from batch response — use single call
                log.warn("obligation_id {} missing from matchBatch response, falling back to single match", oblId);
                try {
                    matchResults = matcher.match(ctx.getSessionId(), "map_obligations_controls",
                            oc.matchable(), oc.uncachedMatchable(), BedrockModel.HAIKU);
                } catch (Exception e) {
                    log.warn("Single-match fallback also failed for obligation {}: {}", oblId, e.getMessage());
                    matchResults = List.of();
                }
            }

            for (MatchResult result : matchResults) {
                String controlId = result.controlId();
                if (controlId == null) continue;
                String id = deterministic(oblId, controlId);
                Mapping mapping = new Mapping();
                mapping.setId(id);
                mapping.setSessionId(ctx.getSessionId());
                mapping.setObligationId(oblId);
                mapping.setControlId(controlId);
                mapping.setMappingConfidence(result.confidence());
                mapping.setSemanticReason(result.reason());
                String typeStr = result.mappingType();
                try { mapping.setMappingType(MappingType.valueOf(typeStr.toLowerCase())); }
                catch (Exception ignored) { mapping.setMappingType(MappingType.partial); }
                double score = mapping.getMappingConfidence() != null ? mapping.getMappingConfidence() : 0;
                mapping.setGapStatus(score >= SATISFIED_CONFIDENCE_THRESHOLD ? GapStatus.satisfied : GapStatus.partial);
                Map<String, String> meta = new HashMap<>();
                meta.put("route", "llm");
                if (result.meetsJurisdictionSpecifics() != null) {
                    meta.put("meets_jurisdiction_specifics", result.meetsJurisdictionSpecifics());
                }
                if (result.missingSpecific() != null && !result.missingSpecific().isBlank()) {
                    meta.put("missing_specific", result.missingSpecific());
                }
                mapping.setMetadata(meta);
                mappingRepository.saveIfNotExists(mapping);
                try {
                    auditLogService.append(ctx.getSessionId(), mapping.getId(),
                            "mapping_created", "pipeline:map-obligations-controls",
                            Map.of("obligation_id", mapping.getObligationId(),
                                    "control_id", mapping.getControlId(),
                                    "confidence", mapping.getMappingConfidence(),
                                    "evidence_sha256s", evidenceHashes));
                } catch (Exception e) {
                    log.warn("Failed to append audit log for mapping {}: {}", mapping.getId(), e.getMessage());
                }
                mappings.add(mapping);
                computed++;
            }
        }
        return new GroupResult(mappings, computed);
    }

    /** Computes candidates (rerank-all primary, KB/structural/all-controls fallbacks), splits cached vs uncached controls. */
    private ObligationCandidate prepareObligation(Obligation obl, List<Control> allControls) {
        String route;
        List<Control> candidates = rerankAllCandidates(obl, allControls);
        if (!candidates.isEmpty()) {
            candidates = applyAnchorRescue(buildQueryText(obl), candidates, allControls);
            route = "rerank-all";
        } else {
            candidates = kbCandidates(obl, allControls);
            if (!candidates.isEmpty()) {
                route = "kb-legacy";
            } else {
                candidates = structuralFilter(obl, allControls);
                if (!candidates.isEmpty()) {
                    route = "structural-filter";
                } else if (!allControls.isEmpty()) {
                    candidates = allControls.stream().limit(MAX_CANDIDATE_CONTROLS).toList();
                    route = "all-controls-fallback";
                } else {
                    route = "none";
                }
            }
        }

        List<Mapping> cachedMappings = new ArrayList<>();
        int reusedCount = 0;
        List<Control> uncached = new ArrayList<>();

        for (Control ctrl : candidates) {
            String mappingId = deterministic(obl.getId(), ctrl.getId());
            Optional<Mapping> existing = mappingRepository.findById(mappingId);
            if (existing.isPresent()) {
                Mapping cached = existing.get();
                if (cached.getMetadata() == null || !cached.getMetadata().containsKey("route")) {
                    Map<String, String> meta = cached.getMetadata() != null
                            ? new HashMap<>(cached.getMetadata()) : new HashMap<>();
                    meta.put("route", "cached");
                    cached.setMetadata(meta);
                    mappingRepository.save(cached);
                }
                cachedMappings.add(cached);
                reusedCount++;
            } else {
                uncached.add(ctrl);
            }
        }

        MatchableObligation matchable = new MatchableObligation(
                obl.getId(), obl.getSubject(), obl.getAction(),
                obl.getRiskCategory(), obl.getRegulatoryPenaltyRange());
        List<MatchableControl> uncachedMatchable = uncached.stream()
                .map(c -> new MatchableControl(
                        c.getId(), c.getDescription(),
                        c.getCategory() != null ? c.getCategory().name() : null,
                        c.getMappedStandards()))
                .toList();

        return new ObligationCandidate(obl, matchable, uncachedMatchable, cachedMappings, reusedCount, route, candidates.size());
    }

    /** Query text for rerank-all/anchor matching: subject + action + conditions, skipping blanks. RiskCategory is deliberately excluded — it is the literal string "UNKNOWN" in real data. */
    static String buildQueryText(Obligation obl) {
        List<String> parts = new ArrayList<>();
        if (obl.getSubject() != null && !obl.getSubject().isBlank()) parts.add(obl.getSubject());
        if (obl.getAction() != null && !obl.getAction().isBlank()) parts.add(obl.getAction());
        if (obl.getConditions() != null) {
            for (String c : obl.getConditions()) {
                if (c != null && !c.isBlank()) parts.add(c);
            }
        }
        return String.join(" ", parts);
    }

    /**
     * Primary candidate-retrieval path: reranks the ENTIRE control set against the
     * obligation in a single Cohere rerank call and returns the top-N. The reranker
     * itself already fails safe (returns first-N unranked on error/timeout), and the
     * caller already falls back on an empty result, so no chunking/guard is needed
     * here — see MatcherRecallEvalTest (config R) for the offline evaluation that
     * validated a single rerank-all-423 call against real Bedrock (recall@20 7/9,
     * latencies in the hundreds of ms, no truncation).
     */
    private List<Control> rerankAllCandidates(Obligation obl, List<Control> allControls) {
        if (allControls.isEmpty() || !reranker.isEnabled()) return List.of();
        String query = buildQueryText(obl);
        if (query.isBlank()) return List.of();

        try {
            List<Reranker.RankedItem> items = allControls.stream()
                    .map(c -> new Reranker.RankedItem(c.getId(),
                            c.getDescription() != null ? c.getDescription() : ""))
                    .toList();
            List<Reranker.RankedItem> reranked = reranker.rerank(query, items, RERANK_TOP_N);
            if (reranked == null || reranked.isEmpty()) return List.of();

            if (log.isDebugEnabled()) {
                log.debug("rerankAllCandidates: obligation {} -> {} candidates, top score {}",
                        obl.getId(), reranked.size(), reranked.get(0).score());
            }

            Map<String, Control> controlIndex = new HashMap<>(allControls.size() * 2);
            for (Control ctrl : allControls) {
                if (ctrl.getId() != null) controlIndex.put(ctrl.getId(), ctrl);
            }

            return reranked.stream()
                    .map(r -> controlIndex.get(r.id()))
                    .filter(Objects::nonNull)
                    .toList();
        } catch (Exception e) {
            log.warn("rerankAllCandidates failed for obligation {}: {}", obl.getId(), e.getMessage());
            return List.of();
        }
    }

    /**
     * Force-includes any control whose description shares an AML anchor term with the
     * obligation query, even if reranking ranked it outside the top-N. Anchors are kept
     * preferentially over reranked items if MAX_TOTAL_CANDIDATES is exceeded.
     */
    List<Control> applyAnchorRescue(String query, List<Control> reranked, List<Control> allControls) {
        Set<String> queryAnchors = detectAnchors(query);
        if (queryAnchors.isEmpty()) {
            return reranked.size() <= MAX_TOTAL_CANDIDATES ? reranked : reranked.subList(0, MAX_TOTAL_CANDIDATES);
        }

        List<Control> forced = new ArrayList<>();
        for (Control ctrl : allControls) {
            Set<String> descAnchors = detectAnchors(ctrl.getDescription());
            if (!Collections.disjoint(queryAnchors, descAnchors)) {
                forced.add(ctrl);
            }
        }

        LinkedHashSet<Control> merged = new LinkedHashSet<>(forced);
        for (Control ctrl : reranked) {
            if (merged.size() >= MAX_TOTAL_CANDIDATES) break;
            merged.add(ctrl);
        }

        List<Control> result = new ArrayList<>(merged);
        return result.size() <= MAX_TOTAL_CANDIDATES ? result : result.subList(0, MAX_TOTAL_CANDIDATES);
    }

    /** Returns the ANCHOR_LEXICON phrases present in text (case-insensitive, word-boundary aware). */
    static Set<String> detectAnchors(String text) {
        if (text == null || text.isBlank()) return Set.of();
        Set<String> found = new LinkedHashSet<>();
        for (Map.Entry<String, Pattern> e : ANCHOR_PATTERNS.entrySet()) {
            if (e.getValue().matcher(text).find()) {
                found.add(e.getKey());
            }
        }
        return found;
    }

    private List<Control> kbCandidates(Obligation obl, List<Control> allControls) {
        try {
            String query = String.join(" ",
                    obl.getSubject() != null ? obl.getSubject() : "",
                    obl.getAction() != null ? obl.getAction() : "",
                    obl.getRiskCategory() != null ? obl.getRiskCategory() : "").trim();
            if (query.isBlank()) return List.of();

            // Chunks arrive sorted by KB score descending from KnowledgeBaseService
            List<KnowledgeBaseService.RetrievedChunk> chunks =
                    knowledgeBaseService.retrieveControls(query, KB_TOP_K).join();
            if (chunks.isEmpty()) return List.of();

            // Build a controlId → Control index for O(1) lookup
            Map<String, Control> controlIndex = new HashMap<>(allControls.size() * 2);
            for (Control ctrl : allControls) {
                if (ctrl.getId() != null) controlIndex.put(ctrl.getId(), ctrl);
            }

            Set<Control> matchedSet = new LinkedHashSet<>();
            for (KnowledgeBaseService.RetrievedChunk chunk : chunks) {
                if (matchedSet.size() >= MAX_CANDIDATE_CONTROLS) break;

                // B6: prefer controlId from chunk metadata (set at ingest time)
                String metaControlId = chunk.metadata() != null ? chunk.metadata().get("controlId") : null;
                if (metaControlId != null) {
                    Control ctrl = controlIndex.get(metaControlId);
                    if (ctrl != null && matchedSet.add(ctrl)) {
                        continue;
                    }
                }

                // Fallback: substring match on description — remains until KB is re-indexed with metadata
                String chunkText = chunk.text() != null ? chunk.text().toLowerCase() : "";
                for (Control ctrl : allControls) {
                    if (matchedSet.contains(ctrl)) continue;
                    String desc = ctrl.getDescription() != null ? ctrl.getDescription().toLowerCase() : "";
                    if (!desc.isBlank() && chunkText.contains(desc.substring(0, Math.min(desc.length(), 40)))) {
                        matchedSet.add(ctrl);
                        break;
                    }
                }
            }

            if (matchedSet.isEmpty()) return List.of();
            List<Control> matched = new ArrayList<>(matchedSet);

            // B8: rerank candidates by relevance to the obligation query, return top-N
            List<Reranker.RankedItem> items = matched.stream()
                    .map(c -> new Reranker.RankedItem(c.getId(),
                            c.getDescription() != null ? c.getDescription() : ""))
                    .toList();
            List<Reranker.RankedItem> reranked = reranker.rerank(query, items, RERANK_TOP_N);

            // Map back to Control objects preserving rerank order
            return reranked.stream()
                    .map(r -> controlIndex.get(r.id()))
                    .filter(Objects::nonNull)
                    .toList();

        } catch (Exception e) {
            log.warn("KB candidate retrieval failed for obligation {}: {}", obl.getId(), e.getMessage());
            return List.of();
        }
    }

    private List<Control> structuralFilter(Obligation obl, List<Control> controls) {
        return controls.stream()
                .filter(ctrl -> {
                    if (obl.getRiskCategory() != null && ctrl.getCategory() != null) {
                        if (ctrl.getCategory().name().equalsIgnoreCase(obl.getRiskCategory())) return true;
                    }
                    if (obl.getSubject() != null && ctrl.getMappedStandards() != null) {
                        return ctrl.getMappedStandards().stream()
                                .anyMatch(s -> s.toLowerCase().contains(obl.getSubject().toLowerCase()));
                    }
                    if (obl.getAction() != null && ctrl.getMappedStandards() != null) {
                        return ctrl.getMappedStandards().stream()
                                .anyMatch(s -> s.toLowerCase().contains(obl.getAction().toLowerCase()));
                    }
                    return false;
                })
                .limit(MAX_CANDIDATE_CONTROLS)
                .toList();
    }

    /** Produces a stable mapping ID from an (obligationId, controlId) pair. */
    static String deterministic(String obligationId, String controlId) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest((obligationId + "#" + controlId).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return "MAP-" + sb.substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
