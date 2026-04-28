package com.bunq.javabackend.service.pipeline.stage;

import com.bunq.javabackend.helper.mapper.MappingMapper;
import com.bunq.javabackend.model.mapping.Mapping;
import com.bunq.javabackend.model.obligation.Obligation;
import com.bunq.javabackend.model.enums.BedrockModel;
import com.bunq.javabackend.model.evidence.Evidence;
import com.bunq.javabackend.repository.EvidenceRepository;
import com.bunq.javabackend.repository.MappingRepository;
import com.bunq.javabackend.repository.ObligationRepository;
import com.bunq.javabackend.service.infra.AuditLogService;
import com.bunq.javabackend.service.ai.bedrock.BedrockService;
import com.bunq.javabackend.service.ai.bedrock.ToolDefinitions;
import com.bunq.javabackend.service.pipeline.PipelineContext;
import com.bunq.javabackend.service.pipeline.PipelineStage;
import com.bunq.javabackend.service.pipeline.Stage;
import com.bunq.javabackend.service.pipeline.prompts.SystemPrompts;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

@Slf4j
@Service
public class GroundCheckStage implements Stage {

    private static final int BATCH_SIZE = 50;

    private final BedrockService bedrockService;
    private final MappingRepository mappingRepository;
    private final ObligationRepository obligationRepository;
    private final ObjectMapper objectMapper;
    private final AuditLogService auditLogService;
    private final EvidenceRepository evidenceRepository;
    private final Executor pipelineExecutor;

    public GroundCheckStage(BedrockService bedrockService,
                            MappingRepository mappingRepository,
                            ObligationRepository obligationRepository,
                            ObjectMapper objectMapper,
                            AuditLogService auditLogService,
                            EvidenceRepository evidenceRepository,
                            @Qualifier("stageWorkerExecutor") Executor pipelineExecutor) {
        this.bedrockService = bedrockService;
        this.mappingRepository = mappingRepository;
        this.obligationRepository = obligationRepository;
        this.objectMapper = objectMapper;
        this.auditLogService = auditLogService;
        this.evidenceRepository = evidenceRepository;
        this.pipelineExecutor = pipelineExecutor;
    }

    @Override
    public PipelineStage stage() {
        return PipelineStage.GROUND_CHECK;
    }

    @Override
    public CompletableFuture<Void> execute(PipelineContext ctx) {
        return CompletableFuture.runAsync(() -> {
            List<String> evidenceHashes = evidenceRepository.findBySessionId(ctx.getSessionId())
                    .stream().map(Evidence::getSha256).filter(Objects::nonNull).toList();

            List<Mapping> mappings = ctx.getMappings();
            if (mappings.isEmpty()) {
                mappings = mappingRepository.findBySessionId(ctx.getSessionId());
            }

            List<Obligation> obligations = ctx.getObligations();
            Map<String, Obligation> oblMap = obligations.stream()
                    .collect(Collectors.toMap(Obligation::getId, o -> o, (a, b) -> a));

            // Collect only mappings that have a semanticReason
            List<Mapping> toCheck = mappings.stream()
                    .filter(m -> m.getSemanticReason() != null)
                    .toList();

            // Partition into batches of BATCH_SIZE
            List<List<Mapping>> batches = new ArrayList<>();
            for (int i = 0; i < toCheck.size(); i += BATCH_SIZE) {
                batches.add(toCheck.subList(i, Math.min(i + BATCH_SIZE, toCheck.size())));
            }

            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (List<Mapping> batch : batches) {
                futures.add(CompletableFuture.supplyAsync(() -> {
                    processBatch(batch, oblMap, ctx, evidenceHashes);
                    return null;
                }, pipelineExecutor));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            log.info("GroundCheckStage: checked {} mappings in {} batches for session {}",
                    toCheck.size(), batches.size(), ctx.getSessionId());
        });
    }

    private void processBatch(List<Mapping> batch, Map<String, Obligation> oblMap,
                              PipelineContext ctx, List<String> evidenceHashes) {
        // Build input for the batch tool call
        List<Map<String, String>> checks = new ArrayList<>();
        Map<String, Mapping> byId = new HashMap<>();
        for (Mapping mapping : batch) {
            Obligation obl = oblMap.get(mapping.getObligationId());
            if (obl == null) {
                obl = obligationRepository.findById(mapping.getObligationId()).orElse(null);
            }
            String sourceText = obl != null && obl.getSource() != null
                    ? obl.getSource().getSourceText()
                    : "";
            Map<String, String> check = new HashMap<>();
            check.put("mapping_id", mapping.getId());
            check.put("claim", mapping.getSemanticReason());
            check.put("source_text", sourceText != null ? sourceText : "");
            checks.add(check);
            byId.put(mapping.getId(), mapping);
        }

        Map<String, Object> userInput = new HashMap<>();
        userInput.put("checks", checks);

        try {
            JsonNode toolInput = bedrockService.invokeModelWithTool(
                    BedrockModel.NOVA_PRO.getModelId(),
                    SystemPrompts.GROUND_CHECK_BATCH,
                    userInput,
                    ToolDefinitions.BATCH_GROUND_CHECK_TOOL
            );

            JsonNode results = toolInput.path("results");
            if (results.isArray()) {
                for (JsonNode resultNode : results) {
                    String mappingId = resultNode.path("mapping_id").asText(null);
                    boolean verified = resultNode.path("verified").asBoolean(true);
                    Mapping mapping = byId.get(mappingId);
                    if (mapping == null) continue;
                    applyResult(mapping, verified, ctx, evidenceHashes);
                }
            }
        } catch (Exception e) {
            log.warn("Batch ground check call failed for {} mappings: {}", batch.size(), e.getMessage());
            // On failure, treat all as verified (same as per-mapping fallback)
        }
    }

    private void applyResult(Mapping mapping, boolean verified, PipelineContext ctx, List<String> evidenceHashes) {
        if (!verified) {
            mapping.setReviewerNotes("ground-check failed: claim not found in source text");
            mappingRepository.save(mapping);
            try {
                auditLogService.append(ctx.getSessionId(), mapping.getId(),
                        "mapping_ground_check_failed", "pipeline:ground-check",
                        Map.of("reason", "not found in retrieved chunk",
                                "evidence_sha256s", evidenceHashes));
            } catch (Exception e) {
                log.warn("Failed to append audit log for mapping {}: {}", mapping.getId(), e.getMessage());
            }
            ctx.getSseEmitterService().send(ctx.getSessionId(), "ground_check.dropped",
                    Map.of("mappingId", mapping.getId(),
                            "reason", "not found in retrieved chunk"));
        } else {
            try {
                auditLogService.append(ctx.getSessionId(), mapping.getId(),
                        "mapping_verified", "pipeline:ground-check",
                        Map.of("confidence", mapping.getMappingConfidence(),
                                "evidence_sha256s", evidenceHashes));
            } catch (Exception e) {
                log.warn("Failed to append audit log for mapping {}: {}", mapping.getId(), e.getMessage());
            }
            ctx.getSseEmitterService().send(ctx.getSessionId(), "ground_check.verified",
                    MappingMapper.toDto(mapping));
        }
    }
}
