package com.bunq.javabackend.service.pipeline.stage;

import com.bunq.javabackend.helper.mapper.MappingMapper;
import com.bunq.javabackend.model.mapping.Mapping;
import com.bunq.javabackend.model.obligation.Obligation;
import com.bunq.javabackend.model.enums.BedrockModel;
import com.bunq.javabackend.model.evidence.Evidence;
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
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Instant;
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

            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (Mapping mapping : mappings) {
                if (mapping.getSemanticReason() == null) continue;

                Obligation resolvedObl = oblMap.get(mapping.getObligationId());
                if (resolvedObl == null) {
                    resolvedObl = obligationRepository.findById(mapping.getObligationId()).orElse(null);
                }
                final Obligation obl = resolvedObl;

                futures.add(CompletableFuture.supplyAsync(() -> {
                    String sourceText = obl != null && obl.getSource() != null
                            ? obl.getSource().getSourceText()
                            : "";

                    boolean verified = groundCheck(mapping.getSemanticReason(), sourceText);

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
                    return null;
                }, pipelineExecutor));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            log.info("GroundCheckStage: verified {} mappings for session {}", mappings.size(), ctx.getSessionId());
        });
    }

    private boolean groundCheck(String semanticReason, String sourceText) {
        if (sourceText == null || sourceText.isBlank()) return true;
        try {
            HashMap<String, Object> userInput = new HashMap<String, Object>();
            userInput.put("claim", semanticReason);
            userInput.put("source_text", sourceText);

            JsonNode toolInput = bedrockService.invokeModelWithTool(
                    BedrockModel.NOVA_LITE.getModelId(),
                    SystemPrompts.GROUND_CHECK,
                    userInput,
                    ToolDefinitions.GROUND_CHECK_TOOL
            );

            return toolInput.path("verified").asBoolean(true);
        } catch (Exception e) {
            log.warn("Ground check call failed: {}", e.getMessage());
            return true;
        }
    }
}
