package com.bunq.javabackend.service.pipeline.stage;

import com.bunq.javabackend.helper.mapper.ControlMapper;
import com.bunq.javabackend.model.control.Control;
import com.bunq.javabackend.model.document.Document;
import com.bunq.javabackend.model.enums.BedrockModel;
import com.bunq.javabackend.model.enums.ControlCategory;
import com.bunq.javabackend.model.enums.ControlType;
import com.bunq.javabackend.model.session.Session;
import com.bunq.javabackend.repository.ControlRepository;
import com.bunq.javabackend.repository.DocumentRepository;
import com.bunq.javabackend.repository.SessionRepository;
import com.bunq.javabackend.service.ai.bedrock.BedrockService;
import com.bunq.javabackend.service.ai.bedrock.ToolDefinitions;
import com.bunq.javabackend.service.pipeline.IngestedDocument;
import com.bunq.javabackend.service.pipeline.PipelineContext;
import com.bunq.javabackend.service.pipeline.PipelineStage;
import com.bunq.javabackend.service.pipeline.Stage;
import com.bunq.javabackend.service.pipeline.TextChunker;
import com.bunq.javabackend.service.pipeline.prompts.SystemPrompts;
import com.bunq.javabackend.util.IdGenerator;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Slf4j
@Service
public class ExtractControlsStage implements Stage {

    private final BedrockService bedrockService;
    private final ControlRepository controlRepository;
    private final DocumentRepository documentRepository;
    private final SessionRepository sessionRepository;
    private final ObjectMapper objectMapper;
    private final S3Client s3Client;
    private final Executor pipelineExecutor;

    @Value("${aws.s3.uploads-bucket}")
    private String uploadsBucket;

    public ExtractControlsStage(BedrockService bedrockService,
                                ControlRepository controlRepository,
                                DocumentRepository documentRepository,
                                SessionRepository sessionRepository,
                                ObjectMapper objectMapper,
                                S3Client s3Client,
                                @Qualifier("stageWorkerExecutor") Executor pipelineExecutor) {
        this.bedrockService = bedrockService;
        this.controlRepository = controlRepository;
        this.documentRepository = documentRepository;
        this.sessionRepository = sessionRepository;
        this.objectMapper = objectMapper;
        this.s3Client = s3Client;
        this.pipelineExecutor = pipelineExecutor;
    }

    @Override
    public PipelineStage stage() {
        return PipelineStage.EXTRACT_CONTROLS;
    }

    @Override
    public CompletableFuture<Void> execute(PipelineContext ctx) {
        // Fix 5a: pass pipelineExecutor to the outer runAsync (was missing — ran on ForkJoinPool.commonPool)
        return CompletableFuture.runAsync(() -> {
            String policyText = ctx.getPolicy();
            if (policyText == null || policyText.isBlank()) {
                ctx.getSseEmitterService().send(ctx.getSessionId(), "stage.skipped",
                        Map.of("stage", PipelineStage.EXTRACT_CONTROLS, "reason", "no policy text available"));
                log.info("ExtractControlsStage: no policy text for session {}, skipping", ctx.getSessionId());
                return;
            }

            // Fix 6: use ingestedDocuments when available to avoid per-id DynamoDB lookups
            List<String> policyDocIds;
            List<IngestedDocument> ingested = ctx.getIngestedDocuments();
            if (ingested != null && !ingested.isEmpty()) {
                policyDocIds = ingested.stream()
                        .filter(d -> "policy".equalsIgnoreCase(d.kind()))
                        .map(IngestedDocument::documentId)
                        .toList();
            } else {
                List<String> documentIds = sessionRepository.findById(ctx.getSessionId())
                        .map(Session::getDocumentIds)
                        .orElse(List.of());
                policyDocIds = documentIds.stream()
                        .filter(id -> {
                            Optional<Document> doc = documentRepository.findById(id);
                            return doc.isPresent() && "policy".equals(doc.get().getKind());
                        })
                        .toList();
            }

            if (policyDocIds.isEmpty()) {
                log.info("No policy-kind documents for session {}; running single Bedrock extraction", ctx.getSessionId());
                runBedrockExtraction(ctx, policyText, null, ctx.getControls());
                return;
            }

            List<Control> collectedControls = Collections.synchronizedList(new ArrayList<>());

            List<CompletableFuture<Void>> futures = policyDocIds.stream()
                    .map(docId -> CompletableFuture.runAsync(() -> {
                        Document doc = documentRepository.findById(docId).orElse(null);
                        if (doc == null) {
                            log.warn("Document {} not found in library; skipping", docId);
                            return;
                        }

                        String loaded = loadExtractedText(doc);
                        String textToExtract = (loaded != null && !loaded.isBlank())
                                ? loaded
                                : policyText;
                        log.info("Cold extraction for document {} in session {}", doc.getId(), ctx.getSessionId());
                        runBedrockExtraction(ctx, textToExtract, doc, collectedControls);
                    }, pipelineExecutor))
                    .toList();

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            ctx.getControls().addAll(collectedControls);
        }, pipelineExecutor);
    }

    private String loadExtractedText(Document doc) {
        if (doc.getExtractedText() != null) {
            return doc.getExtractedText();
        }
        if (doc.getExtractionS3Key() != null) {
            return s3Client.getObjectAsBytes(GetObjectRequest.builder()
                            .bucket(uploadsBucket)
                            .key(doc.getExtractionS3Key())
                            .build())
                    .asUtf8String();
        }
        return null;
    }

    // Fix 5b: chunk policy text and fan out parallel Bedrock calls, dedup by control ID
    private void runBedrockExtraction(PipelineContext ctx, String text, Document doc, List<Control> sink) {
        List<String> chunks = TextChunker.chunk(text);
        String documentId = doc != null ? doc.getId() : null;
        log.info("Splitting doc {} ({} chars) into {} chunks", documentId, text.length(), chunks.size());

        int totalChunks = chunks.size();
        List<CompletableFuture<List<Control>>> futures = new ArrayList<>(totalChunks);
        for (int i = 0; i < totalChunks; i++) {
            final int chunkIdx = i;
            final String chunkText = chunks.get(i);
            futures.add(CompletableFuture.supplyAsync(
                    () -> extractChunk(ctx, chunkText, doc, chunkIdx, totalChunks),
                    pipelineExecutor));
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        // Dedup controls from overlapping chunks by deterministic ID
        Map<String, Control> seen = new LinkedHashMap<>();
        for (CompletableFuture<List<Control>> f : futures) {
            for (Control ctrl : f.join()) {
                seen.putIfAbsent(ctrl.getId(), ctrl);
            }
        }

        for (Control ctrl : seen.values()) {
            controlRepository.save(ctrl);
            sink.add(ctrl);
            ctx.getSseEmitterService().send(ctx.getSessionId(), "control.extracted",
                    ControlMapper.toDto(ctrl));
        }

        // Fix 1: targeted update instead of full putItem to avoid clobbering parallel writers
        if (doc != null) {
            documentRepository.markControlsExtracted(doc.getId());
        }

        log.info("ExtractControlsStage: extracted {} controls for session {} (document={}, chunks={})",
                seen.size(), ctx.getSessionId(), documentId, totalChunks);
    }

    private List<Control> extractChunk(PipelineContext ctx, String chunkText, Document doc,
                                       int chunkIdx, int totalChunks) {
        String documentId = doc != null ? doc.getId() : null;
        log.info("Extracting chunk {}/{} for document {} (session {})",
                chunkIdx + 1, totalChunks, documentId, ctx.getSessionId());

        Map<String, String> userInput = Map.of("policy_text", chunkText, "policy_id", "POL-" + ctx.getSessionId());

        JsonNode toolInput = bedrockService.invokeModelWithTool(
                ctx.getSessionId(), "extract_controls",
                BedrockModel.SONNET.getModelId(),
                SystemPrompts.EXTRACT_CONTROLS,
                userInput,
                ToolDefinitions.EXTRACT_CONTROLS_TOOL
        );

        return parseControls(ctx, chunkText, toolInput, ctx.getSessionId(), documentId);
    }

    private List<Control> parseControls(PipelineContext ctx, String sourceText,
                                        JsonNode toolInput, String sessionId, String documentId) {
        List<Control> result = new ArrayList<>();
        if (toolInput == null || toolInput.isMissingNode()) return result;

        JsonNode arrayNode = toolInput.isArray() ? toolInput : toolInput.path("controls");
        if (!arrayNode.isArray()) return result;

        for (JsonNode node : arrayNode) {
            try {
                Control ctrl = new Control();
                String ctrlDesc = node.path("description").asText(null);
                ctrl.setId(IdGenerator.controlId(sessionId, documentId, ctrlDesc));
                ctrl.setSessionId(sessionId);
                ctrl.setDocumentId(documentId);
                ctrl.setDescription(ctrlDesc);
                ctrl.setOwner(node.path("owner").asText(null));

                String typeStr = node.path("control_type").asText(null);
                if (typeStr != null) {
                    try { ctrl.setControlType(ControlType.valueOf(typeStr.toLowerCase())); } catch (Exception e) { log.warn("Unknown ControlType '{}', skipping: {}", typeStr, e.getMessage()); }
                }
                String catStr = node.path("category").asText(null);
                if (catStr != null) {
                    try { ctrl.setCategory(ControlCategory.valueOf(catStr.toLowerCase())); } catch (Exception e) { log.warn("Unknown ControlCategory '{}', skipping: {}", catStr, e.getMessage()); }
                }

                JsonNode standardsNode = node.path("mapped_standards");
                if (standardsNode.isArray()) {
                    ArrayList<String> standards = new ArrayList<String>();
                    standardsNode.forEach(s -> standards.add(s.asText()));
                    ctrl.setMappedStandards(standards);
                }

                String snippet = node.path("source_text_snippet").asText(null);

                if (!isGrounded(snippet, sourceText)) {
                    String preview = snippet != null && snippet.length() > 60
                            ? snippet.substring(0, 60) + "..." : snippet;
                    log.warn("Control rejected (snippet not grounded in source); description='{}' snippet='{}'",
                            ctrl.getDescription(), preview);
                    ctx.getSseEmitterService().send(sessionId, "control.rejected",
                            Map.of("reason", "snippet_not_grounded",
                                    "subject", ctrl.getDescription() != null ? ctrl.getDescription() : "",
                                    "snippet_preview", preview != null ? preview : ""));
                    continue;
                }

                result.add(ctrl);
            } catch (Exception e) {
                log.warn("Failed to parse control node: {}", e.getMessage());
            }
        }
        return result;
    }

    // Fix 4: 100-char prefix check + 80% token-overlap fallback (mirrors ExtractObligationsStage)
    private static boolean isGrounded(String snippet, String sourceText) {
        if (snippet == null || snippet.isBlank() || sourceText == null) return false;
        String n = normalize(snippet);
        if (n.isEmpty()) return false;
        String haystack = normalize(sourceText);
        String needle = n.length() > 100 ? n.substring(0, 100) : n;
        if (haystack.contains(needle)) return true;
        String[] tokens = n.split(" ");
        long meaningful = 0;
        long found = 0;
        for (String token : tokens) {
            if (token.length() > 3) {
                meaningful++;
                if (haystack.contains(token)) found++;
            }
        }
        return meaningful > 0 && (double) found / meaningful >= 0.8;
    }

    private static String normalize(String s) {
        return s == null ? "" : s.toLowerCase().replaceAll("\\s+", " ").trim();
    }
}
