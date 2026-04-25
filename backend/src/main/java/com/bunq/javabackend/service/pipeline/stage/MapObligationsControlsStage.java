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
import com.bunq.javabackend.service.AuditLogService;
import com.bunq.javabackend.service.bedrock.MatchResult;
import com.bunq.javabackend.service.bedrock.MatchableControl;
import com.bunq.javabackend.service.bedrock.MatchableObligation;
import com.bunq.javabackend.service.bedrock.ObligationControlMatcher;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Slf4j
@Service
public class MapObligationsControlsStage implements Stage {

    private static final int BATCH_SIZE = 10;
    private static final int MAX_CANDIDATE_CONTROLS = 20;

    private final ObligationControlMatcher matcher;
    private final MappingRepository mappingRepository;
    private final ObligationRepository obligationRepository;
    private final ControlRepository controlRepository;
    private final AuditLogService auditLogService;
    private final EvidenceRepository evidenceRepository;
    private final Executor pipelineExecutor;

    public MapObligationsControlsStage(ObligationControlMatcher matcher, MappingRepository mappingRepository,
                                       ObligationRepository obligationRepository, ControlRepository controlRepository,
                                       AuditLogService auditLogService, EvidenceRepository evidenceRepository,
                                       @Qualifier("pipelineExecutor") Executor pipelineExecutor) {
        this.matcher = matcher;
        this.mappingRepository = mappingRepository;
        this.obligationRepository = obligationRepository;
        this.controlRepository = controlRepository;
        this.auditLogService = auditLogService;
        this.evidenceRepository = evidenceRepository;
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
        });
    }

    private record BatchResult(List<Mapping> mappings, int computed, int reused) {}

    private BatchResult processBatch(List<Obligation> batch, List<Control> allControls,
                                     PipelineContext ctx, List<String> evidenceHashes) {
        List<CompletableFuture<ObligationResult>> futures = new ArrayList<>(batch.size());
        for (Obligation obl : batch) {
            futures.add(CompletableFuture.supplyAsync(
                    () -> processObligation(obl, allControls, ctx, evidenceHashes), pipelineExecutor));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        List<Mapping> results = new ArrayList<>();
        int computed = 0;
        int reused = 0;
        for (CompletableFuture<ObligationResult> f : futures) {
            ObligationResult r = f.join();
            results.addAll(r.mappings);
            computed += r.computed;
            reused += r.reused;
        }
        return new BatchResult(results, computed, reused);
    }

    private static class ObligationResult {
        final List<Mapping> mappings = new ArrayList<>();
        int computed = 0;
        int reused = 0;
    }

    private ObligationResult processObligation(Obligation obl, List<Control> allControls,
                                               PipelineContext ctx, List<String> evidenceHashes) {
        ObligationResult out = new ObligationResult();
        List<Control> candidates = structuralFilter(obl, allControls);
        if (candidates.isEmpty() && !allControls.isEmpty()) {
            candidates = allControls.stream().limit(MAX_CANDIDATE_CONTROLS).toList();
        }
        if (candidates.isEmpty()) {
            return out;
        }

        // Split candidates: cached (skip Bedrock) vs uncached (single batched matcher call)
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
                out.mappings.add(cached);
                out.reused++;
            } else {
                uncached.add(ctrl);
            }
        }

        if (uncached.isEmpty()) return out;

        MatchableObligation matchableObl = new MatchableObligation(
                obl.getId(), obl.getSubject(), obl.getAction(),
                obl.getRiskCategory(), obl.getRegulatoryPenaltyRange());
        List<MatchableControl> matchableControls = uncached.stream()
                .map(c -> new MatchableControl(
                        c.getId(), c.getDescription(),
                        c.getCategory() != null ? c.getCategory().name() : null,
                        c.getMappedStandards()))
                .toList();
        List<MatchResult> matchResults = matcher.match(matchableObl, matchableControls);

        for (MatchResult result : matchResults) {
            String controlId = result.controlId();
            if (controlId == null) continue;
            String id = deterministic(obl.getId(), controlId);
            Mapping mapping = new Mapping();
            mapping.setId(id);
            mapping.setSessionId(ctx.getSessionId());
            mapping.setObligationId(obl.getId());
            mapping.setControlId(controlId);
            mapping.setMappingConfidence(result.confidence());
            mapping.setSemanticReason(result.reason());
            String typeStr = result.mappingType();
            try { mapping.setMappingType(MappingType.valueOf(typeStr.toLowerCase())); }
            catch (Exception ignored) { mapping.setMappingType(MappingType.partial); }
            double score = mapping.getMappingConfidence() != null ? mapping.getMappingConfidence() : 0;
            mapping.setGapStatus(score >= 50 ? GapStatus.satisfied : GapStatus.partial);
            Map<String, String> meta = new HashMap<>();
            meta.put("route", "llm");
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
            out.mappings.add(mapping);
            out.computed++;
        }
        return out;
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
