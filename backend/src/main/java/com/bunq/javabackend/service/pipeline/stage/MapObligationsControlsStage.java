package com.bunq.javabackend.service.pipeline.stage;

import com.bunq.javabackend.helper.mapper.MappingMapper;
import com.bunq.javabackend.model.control.Control;
import com.bunq.javabackend.model.mapping.Mapping;
import com.bunq.javabackend.model.obligation.Obligation;
import com.bunq.javabackend.model.enums.BedrockModel;
import com.bunq.javabackend.model.enums.GapStatus;
import com.bunq.javabackend.model.enums.MappingType;
import com.bunq.javabackend.model.evidence.Evidence;
import com.bunq.javabackend.repository.ControlRepository;
import com.bunq.javabackend.repository.EvidenceRepository;
import com.bunq.javabackend.repository.MappingRepository;
import com.bunq.javabackend.repository.ObligationRepository;
import com.bunq.javabackend.service.AuditLogService;
import com.bunq.javabackend.service.BedrockService;
import com.bunq.javabackend.service.bedrock.ToolDefinitions;
import com.bunq.javabackend.service.pipeline.PipelineContext;
import com.bunq.javabackend.service.pipeline.PipelineStage;
import com.bunq.javabackend.service.pipeline.Stage;
import com.bunq.javabackend.service.pipeline.prompts.SystemPrompts;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
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

@Slf4j
@Service
@RequiredArgsConstructor
public class MapObligationsControlsStage implements Stage {

    private static final int BATCH_SIZE = 10;
    private static final int MAX_CANDIDATE_CONTROLS = 20;

    private final BedrockService bedrockService;
    private final MappingRepository mappingRepository;
    private final ObligationRepository obligationRepository;
    private final ControlRepository controlRepository;
    private final ObjectMapper objectMapper;
    private final AuditLogService auditLogService;
    private final EvidenceRepository evidenceRepository;

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
        List<Mapping> results = new ArrayList<>();
        int computed = 0;
        int reused = 0;
        for (Obligation obl : batch) {
            List<Control> candidates = structuralFilter(obl, allControls);
            if (candidates.isEmpty() && !allControls.isEmpty()) {
                candidates = allControls.stream().limit(MAX_CANDIDATE_CONTROLS).toList();
            }
            if (candidates.isEmpty()) {
                continue;
            }

            for (Control ctrl : candidates) {
                String mappingId = deterministic(obl.getId(), ctrl.getId());
                Optional<Mapping> existing = mappingRepository.findById(mappingId);
                if (existing.isPresent()) {
                    Mapping cached = existing.get();
                    // ensure route tag is present; only write back if it was absent
                    if (cached.getMetadata() == null || !cached.getMetadata().containsKey("route")) {
                        Map<String, String> meta = cached.getMetadata() != null
                                ? new HashMap<>(cached.getMetadata()) : new HashMap<>();
                        meta.put("route", "cached");
                        cached.setMetadata(meta);
                        mappingRepository.save(cached);
                    }
                    results.add(cached);
                    reused++;
                } else {
                    List<Mapping> semanticMappings = semanticMatch(obl, List.of(ctrl), ctx.getSessionId(), mappingId);
                    for (Mapping m : semanticMappings) {
                        Map<String, String> meta = new HashMap<>();
                        meta.put("route", "llm");
                        m.setMetadata(meta);
                        mappingRepository.saveIfNotExists(m);
                        try {
                            auditLogService.append(ctx.getSessionId(), m.getId(),
                                    "mapping_created", "pipeline:map-obligations-controls",
                                    Map.of("obligation_id", m.getObligationId(),
                                            "control_id", m.getControlId(),
                                            "confidence", m.getMappingConfidence(),
                                            "evidence_sha256s", evidenceHashes));
                        } catch (Exception e) {
                            log.warn("Failed to append audit log for mapping {}: {}", m.getId(), e.getMessage());
                        }
                        results.add(m);
                        computed++;
                    }
                }
            }
        }
        return new BatchResult(results, computed, reused);
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

    private List<Mapping> semanticMatch(Obligation obl, List<Control> candidates,
                                        String sessionId, String mappingId) {
        List<Mapping> mappings = new ArrayList<>();
        try {
            HashMap<String, Object> userInput = new HashMap<String, Object>();
            userInput.put("obligation_id", obl.getId());
            userInput.put("obligation_subject", obl.getSubject());
            userInput.put("obligation_action", obl.getAction());
            userInput.put("obligation_risk_category", obl.getRiskCategory());
            userInput.put("candidate_controls", candidates.stream().map(c -> {
                HashMap<String, Object> m = new HashMap<String, Object>();
                m.put("control_id", c.getId());
                m.put("description", c.getDescription());
                m.put("category", c.getCategory() != null ? c.getCategory().name() : null);
                m.put("mapped_standards", c.getMappedStandards());
                return m;
            }).toList());

            JsonNode toolInput = bedrockService.invokeModelWithTool(
                    BedrockModel.SONNET.getModelId(),
                    SystemPrompts.MATCH_OBLIGATIONS_TO_CONTROLS,
                    userInput,
                    ToolDefinitions.MATCH_OBLIGATION_TO_CONTROLS_TOOL
            );

            JsonNode matchesNode = toolInput.isArray() ? toolInput : toolInput.path("matches");
            if (matchesNode.isArray()) {
                for (JsonNode node : matchesNode) {
                    Mapping mapping = new Mapping();
                    // use deterministic ID only when there is exactly one candidate (per-pair call)
                    // otherwise fall back to per-pair id derived from the returned control_id
                    String controlId = node.path("control_id").asText(null);
                    String id = candidates.size() == 1
                            ? mappingId
                            : deterministic(obl.getId(), controlId != null ? controlId : "");
                    mapping.setId(id);
                    mapping.setSessionId(sessionId);
                    mapping.setObligationId(obl.getId());
                    mapping.setControlId(controlId);
                    mapping.setMappingConfidence(node.path("match_score").asDouble(0.0));
                    mapping.setSemanticReason(node.path("reason").asText(null));

                    String typeStr = node.path("mapping_type").asText("partial");
                    try { mapping.setMappingType(MappingType.valueOf(typeStr.toLowerCase())); }
                    catch (Exception ignored) { mapping.setMappingType(MappingType.partial); }

                    double score = mapping.getMappingConfidence() != null ? mapping.getMappingConfidence() : 0;
                    mapping.setGapStatus(score >= 50 ? GapStatus.satisfied : GapStatus.partial);

                    mappings.add(mapping);
                }
            }
        } catch (Exception e) {
            log.warn("Semantic match failed for obligation {}: {}", obl.getId(), e.getMessage());
        }
        return mappings;
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
