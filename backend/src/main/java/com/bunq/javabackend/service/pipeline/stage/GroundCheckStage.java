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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

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

    private static final int BATCH_SIZE = 20;
    /**
     * Truncation cap for full document text sent to NOVA-PRO. Keeps token cost
     * manageable.
     * Known limitation: obligations from text beyond this offset are verified
     * against a truncated document.
     */
    private static final int DOC_TEXT_MAX_CHARS = 200_000;

    private final BedrockService bedrockService;
    private final MappingRepository mappingRepository;
    private final ObligationRepository obligationRepository;
    private final ObjectMapper objectMapper;
    private final AuditLogService auditLogService;
    private final EvidenceRepository evidenceRepository;
    private final Executor pipelineExecutor;
    private final S3Client s3Client;

    @Value("${aws.s3.uploads-bucket}")
    private String uploadsBucket;

    public GroundCheckStage(BedrockService bedrockService,
            MappingRepository mappingRepository,
            ObligationRepository obligationRepository,
            ObjectMapper objectMapper,
            AuditLogService auditLogService,
            EvidenceRepository evidenceRepository,
            S3Client s3Client,
            @Qualifier("stageWorkerExecutor") Executor pipelineExecutor) {
        this.bedrockService = bedrockService;
        this.mappingRepository = mappingRepository;
        this.obligationRepository = obligationRepository;
        this.objectMapper = objectMapper;
        this.auditLogService = auditLogService;
        this.evidenceRepository = evidenceRepository;
        this.s3Client = s3Client;
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
            // B7: partition into cached (skip LLM re-check) and those that need
            // verification
            List<Mapping> skipped = new ArrayList<>();
            List<Mapping> toCheck = new ArrayList<>();
            for (Mapping m : mappings) {
                if (m.getSemanticReason() == null)
                    continue;
                boolean isCached = m.getMetadata() != null && "cached".equals(m.getMetadata().get("route"));
                boolean alreadyFailed = m.getReviewerNotes() != null
                        && m.getReviewerNotes().contains("ground-check failed");
                if (isCached && !alreadyFailed) {
                    skipped.add(m);
                } else {
                    toCheck.add(m);
                }
            }

            // B7: emit verified SSE for skipped cached mappings so UI sees a uniform stream
            for (Mapping m : skipped) {
                ctx.getSseEmitterService().send(ctx.getSessionId(), "ground_check.verified",
                        MappingMapper.toDto(m));
            }
            log.info("GroundCheck skip: {} cached mappings already verified", skipped.size());

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

            log.info("GroundCheckStage: checked {} mappings in {} batches for session {} (skipped {} cached)",
                    toCheck.size(), batches.size(), ctx.getSessionId(), skipped.size());
        });
    }

    private void processBatch(List<Mapping> batch, Map<String, Obligation> oblMap,
            PipelineContext ctx, List<String> evidenceHashes) {
        // B10: collect distinct documentIds in this batch, then fetch full extracted
        // doc text
        // from S3 (extractions/{docId}.txt) once per docId. This replaces the
        // obligation
        // sourceTextSnippet (a Haiku paraphrase) with the original text the LLM
        // actually saw.
        // Known limitation: obligations from text beyond DOC_TEXT_MAX_CHARS are
        // verified against
        // a truncated document; this keeps NOVA-PRO token cost manageable.
        Map<String, String> docTexts = new HashMap<>();
        for (Mapping mapping : batch) {
            Obligation obl = oblMap.get(mapping.getObligationId());
            if (obl == null) {
                obl = obligationRepository.findById(mapping.getObligationId()).orElse(null);
            }
            String docId = obl != null ? obl.getDocumentId() : null;
            if (docId != null && !docTexts.containsKey(docId)) {
                String s3Key = "extractions/" + docId + ".txt";
                try {
                    String fullText = s3Client.getObjectAsBytes(
                            GetObjectRequest.builder()
                                    .bucket(uploadsBucket)
                                    .key(s3Key)
                                    .build())
                            .asUtf8String();
                    if (fullText.length() > DOC_TEXT_MAX_CHARS) {
                        fullText = fullText.substring(0, DOC_TEXT_MAX_CHARS);
                    }
                    docTexts.put(docId, fullText);
                } catch (NoSuchKeyException e) {
                    log.error("GroundCheck: S3 key {} not found for document {}; batch marked failed", s3Key, docId);
                    // Fail closed: mark all mappings in this batch as failed and abort
                    for (Mapping m : batch) {
                        m.setReviewerNotes("ground-check failed: source document text unavailable");
                        mappingRepository.save(m);
                        ctx.getSseEmitterService().send(ctx.getSessionId(), "ground_check.dropped",
                                Map.of("mappingId", m.getId(), "reason", "source document text unavailable"));
                    }
                    return;
                } catch (Exception e) {
                    log.error("GroundCheck: failed to fetch S3 key {} for document {}: {}; batch marked failed",
                            s3Key, docId, e.getMessage());
                    for (Mapping m : batch) {
                        m.setReviewerNotes("ground-check failed: source document text unavailable");
                        mappingRepository.save(m);
                        ctx.getSseEmitterService().send(ctx.getSessionId(), "ground_check.dropped",
                                Map.of("mappingId", m.getId(), "reason", "source document text unavailable"));
                    }
                    return;
                }
            }
        }

        // Build input for the batch tool call.
        // B10-fix: send each unique document text ONCE in a top-level "documents" map
        // keyed by
        // doc_id. Each check references its doc via "doc_id" instead of inlining the
        // full text
        // repeatedly — this prevents token explosion when multiple mappings share the
        // same document.
        List<Map<String, String>> checks = new ArrayList<>();
        Map<String, Mapping> byId = new HashMap<>();
        for (Mapping mapping : batch) {
            Obligation obl = oblMap.get(mapping.getObligationId());
            if (obl == null) {
                obl = obligationRepository.findById(mapping.getObligationId()).orElse(null);
            }
            String docId = obl != null ? obl.getDocumentId() : null;
            Map<String, String> check = new HashMap<>();
            check.put("mapping_id", mapping.getId());
            check.put("claim", mapping.getSemanticReason());
            // Reference document by id rather than embedding the full text in every check
            // entry.
            // The model receives documents[doc_id] = <text> in the top-level payload.
            check.put("doc_id", docId != null ? docId : "");
            checks.add(check);
            byId.put(mapping.getId(), mapping);
        }

        Map<String, Object> userInput = new HashMap<>();
        userInput.put("documents", docTexts); // unique doc texts, keyed by documentId
        userInput.put("checks", checks);

        try {
            JsonNode toolInput = bedrockService.invokeModelWithTool(
                    ctx.getSessionId(), "ground_check",
                    BedrockModel.NOVA_PRO.getModelId(),
                    SystemPrompts.GROUND_CHECK_BATCH,
                    userInput,
                    ToolDefinitions.BATCH_GROUND_CHECK_TOOL);

            JsonNode results = toolInput.path("results");
            if (results.isArray()) {
                for (JsonNode resultNode : results) {
                    String mappingId = resultNode.path("mapping_id").asText(null);
                    boolean verified = resultNode.path("verified").asBoolean(true);
                    Mapping mapping = byId.get(mappingId);
                    if (mapping == null)
                        continue;
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
