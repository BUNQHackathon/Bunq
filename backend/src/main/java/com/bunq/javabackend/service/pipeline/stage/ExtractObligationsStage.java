package com.bunq.javabackend.service.pipeline.stage;

import com.bunq.javabackend.dto.response.events.StageDeltaEvent;
import com.bunq.javabackend.helper.mapper.ObligationMapper;
import com.bunq.javabackend.model.document.Document;
import com.bunq.javabackend.model.obligation.Obligation;
import com.bunq.javabackend.model.obligation.ObligationSource;
import com.bunq.javabackend.model.enums.BedrockModel;
import com.bunq.javabackend.model.session.Session;
import com.bunq.javabackend.repository.DocumentRepository;
import com.bunq.javabackend.repository.ObligationRepository;
import com.bunq.javabackend.repository.SessionRepository;
import com.bunq.javabackend.service.ai.bedrock.BedrockService;
import com.bunq.javabackend.service.ai.bedrock.ToolDefinitions;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Slf4j
@Service
public class ExtractObligationsStage implements Stage {

    private final BedrockService bedrockService;
    private final ObligationRepository obligationRepository;
    private final DocumentRepository documentRepository;
    private final SessionRepository sessionRepository;
    private final ObjectMapper objectMapper;
    private final S3Client s3Client;
    private final Executor pipelineExecutor;

    @Value("${aws.s3.uploads-bucket}")
    private String uploadsBucket;

    public ExtractObligationsStage(BedrockService bedrockService,
                                   ObligationRepository obligationRepository,
                                   DocumentRepository documentRepository,
                                   SessionRepository sessionRepository,
                                   ObjectMapper objectMapper,
                                   S3Client s3Client,
                                   @Qualifier("stageWorkerExecutor") Executor pipelineExecutor) {
        this.bedrockService = bedrockService;
        this.obligationRepository = obligationRepository;
        this.documentRepository = documentRepository;
        this.sessionRepository = sessionRepository;
        this.objectMapper = objectMapper;
        this.s3Client = s3Client;
        this.pipelineExecutor = pipelineExecutor;
    }

    @Override
    public PipelineStage stage() {
        return PipelineStage.EXTRACT_OBLIGATIONS;
    }

    @Override
    public CompletableFuture<Void> execute(PipelineContext ctx) {
        return CompletableFuture.runAsync(() -> {
            String regulation = ctx.getRegulation();
            if (regulation == null || regulation.isBlank()) {
                log.info("No regulation text for session {}; skipping ExtractObligationsStage", ctx.getSessionId());
                ctx.getSseEmitterService().send(ctx.getSessionId(), "stage.skipped",
                        Map.of("stage", PipelineStage.EXTRACT_OBLIGATIONS, "reason", "no regulation text provided"));
                return;
            }

            List<String> documentIds = sessionRepository.findById(ctx.getSessionId())
                    .map(Session::getDocumentIds)
                    .orElse(List.of());

            List<String> regulationDocIds = documentIds.stream()
                    .filter(id -> {
                        Optional<Document> doc = documentRepository.findById(id);
                        return doc.isPresent() && "regulation".equals(doc.get().getKind());
                    })
                    .toList();

            if (regulationDocIds.isEmpty()) {
                // No regulation-kind documents attached — fall back to single Bedrock call on concatenated text
                log.info("No regulation-kind documents for session {}; running single Bedrock extraction", ctx.getSessionId());
                List<Obligation> singleBuffer = new ArrayList<>();
                runBedrockExtraction(ctx, regulation, null, singleBuffer);
                ctx.getObligations().addAll(singleBuffer);
                return;
            }

            List<Obligation> parallelBuffer = Collections.synchronizedList(new ArrayList<>());
            List<CompletableFuture<Void>> docFutures = new ArrayList<>();

            for (String docId : regulationDocIds) {
                docFutures.add(CompletableFuture.runAsync(() -> {
                    Document doc = documentRepository.findById(docId).orElse(null);
                    if (doc == null) {
                        log.warn("Document {} not found in library; skipping", docId);
                        return;
                    }

                    if (doc.isObligationsExtracted()) {
                        // Cache hit — clone existing obligations into this session
                        List<Obligation> originals = obligationRepository.findByDocumentId(doc.getId());
                        log.info("Cache hit for document {} ({} obligations); cloning into session {}",
                                doc.getId(), originals.size(), ctx.getSessionId());

                        for (Obligation original : originals) {
                            Obligation clone = cloneObligation(original, ctx.getSessionId());
                            obligationRepository.save(clone);
                            parallelBuffer.add(clone);
                            ctx.getSseEmitterService().send(ctx.getSessionId(), "obligation.extracted",
                                    ObligationMapper.toDto(clone));
                        }

                        ctx.getSseEmitterService().send(ctx.getSessionId(), "document.cached",
                                Map.of("documentId", doc.getId(), "kind", "regulation",
                                        "recordsReused", originals.size()));
                    } else {
                        // Cold path — Bedrock extraction; use per-doc text if available, else fall back to ctx.getRegulation()
                        String loaded = loadExtractedText(doc);
                        String textToExtract = (loaded != null && !loaded.isBlank())
                                ? loaded
                                : regulation;
                        log.info("Cold extraction for document {} in session {}", doc.getId(), ctx.getSessionId());
                        runBedrockExtraction(ctx, textToExtract, doc, parallelBuffer);
                    }
                }));
            }

            CompletableFuture.allOf(docFutures.toArray(new CompletableFuture[0])).join();
            ctx.getObligations().addAll(parallelBuffer);
        });
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

    private void runBedrockExtraction(PipelineContext ctx, String text, Document doc, List<Obligation> buffer) {
        List<String> chunks = TextChunker.chunk(text);
        String documentId = doc != null ? doc.getId() : null;
        log.info("Splitting doc {} ({} chars) into {} chunks", documentId, text.length(), chunks.size());

        int totalChunks = chunks.size();
        List<CompletableFuture<List<Obligation>>> futures = new ArrayList<>(totalChunks);
        for (int i = 0; i < totalChunks; i++) {
            final int chunkIdx = i;
            final String chunkText = chunks.get(i);
            futures.add(CompletableFuture.supplyAsync(
                    () -> extractChunk(ctx, chunkText, doc, chunkIdx, totalChunks),
                    pipelineExecutor));
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        List<Obligation> all = new ArrayList<>();
        for (CompletableFuture<List<Obligation>> f : futures) {
            all.addAll(f.join());
        }

        for (Obligation obl : all) {
            obligationRepository.save(obl);
            buffer.add(obl);
            ctx.getSseEmitterService().send(ctx.getSessionId(), "obligation.extracted",
                    ObligationMapper.toDto(obl));
        }

        if (doc != null) {
            doc.setObligationsExtracted(true);
            documentRepository.save(doc);
        }

        log.info("ExtractObligationsStage: extracted {} obligations for session {} (document={}, chunks={})",
                all.size(), ctx.getSessionId(), documentId, totalChunks);
    }

    private List<Obligation> extractChunk(PipelineContext ctx, String chunkText, Document doc,
                                          int chunkIdx, int totalChunks) {
        String documentId = doc != null ? doc.getId() : null;
        log.info("Extracting chunk {}/{} for document {} (session {})",
                chunkIdx + 1, totalChunks, documentId, ctx.getSessionId());

        Map<String, String> userInput = Map.of(
                "regulation_text", chunkText,
                "regulation_id", "REG-" + ctx.getSessionId(),
                "article", "general",
                "paragraph_id", String.valueOf(chunkIdx + 1)
        );

        JsonNode toolInput = bedrockService.invokeModelWithTool(
                ctx.getSessionId(), "extract_obligations",
                BedrockModel.HAIKU.getModelId(),
                SystemPrompts.EXTRACT_OBLIGATIONS,
                userInput,
                ToolDefinitions.EXTRACT_OBLIGATIONS_TOOL
        );

        String regulationName = doc != null
                ? stripPdf(doc.getFilename())
                : "REG-" + ctx.getSessionId();
        return parseObligations(ctx, chunkText, toolInput, ctx.getSessionId(), documentId, regulationName);
    }

    private Obligation cloneObligation(Obligation original, String sessionId) {
        Obligation clone = new Obligation();
        clone.setId(original.getId());  // preserve content-addressable ID so mapping cache works
        clone.setSessionId(sessionId);
        clone.setDocumentId(original.getDocumentId());
        clone.setDeontic(original.getDeontic());
        clone.setSubject(original.getSubject());
        clone.setAction(original.getAction());
        clone.setRiskCategory(original.getRiskCategory());
        clone.setExtractionConfidence(original.getExtractionConfidence());
        clone.setExtractedAt(Instant.now());
        clone.setConditions(original.getConditions());
        clone.setSource(original.getSource());
        clone.setObligationType(original.getObligationType());
        clone.setApplicableJurisdictions(original.getApplicableJurisdictions());
        clone.setApplicableEntities(original.getApplicableEntities());
        clone.setSeverity(original.getSeverity());
        clone.setRegulatoryPenaltyRange(original.getRegulatoryPenaltyRange());
        clone.setRegulationId(original.getRegulationId());
        return clone;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private static String stripPdf(String name) {
        if (name == null) return null;
        return name.endsWith(".pdf") ? name.substring(0, name.length() - 4) : name;
    }

    private List<Obligation> parseObligations(PipelineContext ctx, String chunkText,
                                              JsonNode toolInput, String sessionId, String documentId,
                                              String regulationName) {
        List<Obligation> result = new ArrayList<>();
        if (toolInput == null || toolInput.isMissingNode()) return result;

        // Tool output may be an object with an "obligations" array, or a direct array
        JsonNode arrayNode = toolInput.isArray() ? toolInput : toolInput.path("obligations");
        if (!arrayNode.isArray()) return result;

        for (JsonNode node : arrayNode) {
            try {
                Obligation obl = new Obligation();
                String oblSubject = node.path("subject").asText(null);
                String oblAction  = node.path("action").asText(null);
                obl.setId(IdGenerator.obligationId(documentId, oblSubject, oblAction));
                obl.setSessionId(sessionId);
                obl.setDocumentId(documentId);
                obl.setDeontic(parseEnum(node.path("deontic").asText()));
                obl.setSubject(oblSubject);
                obl.setAction(oblAction);
                obl.setRiskCategory(node.path("risk_category").asText(null));
                obl.setExtractionConfidence(node.path("extraction_confidence").asDouble(0.0));
                obl.setExtractedAt(Instant.now());

                JsonNode conditionsNode = node.path("conditions");
                if (conditionsNode.isArray()) {
                    ArrayList<String> conditions = new ArrayList<String>();
                    conditionsNode.forEach(c -> conditions.add(c.asText()));
                    obl.setConditions(conditions);
                }

                ObligationSource source = new ObligationSource();
                String snippet = node.path("source_text_snippet").asText(null);
                source.setSourceText(snippet);
                source.setRegulation(regulationName);
                source.setArticle(blankToNull(node.path("article").asText(null)));
                source.setSection(blankToNull(node.path("section").asText(null)));
                JsonNode paraNode = node.path("paragraph");
                if (!paraNode.isMissingNode() && !paraNode.isNull() && paraNode.asInt(0) != 0) {
                    source.setParagraph(paraNode.asInt());
                }
                obl.setSource(source);

                if (!isGrounded(snippet, chunkText)) {
                    String preview = snippet != null && snippet.length() > 60
                            ? snippet.substring(0, 60) + "..." : snippet;
                    log.warn("Obligation rejected (snippet not grounded in chunk); subject='{}' snippet='{}'",
                            obl.getSubject(), preview);
                    ctx.getSseEmitterService().send(sessionId, "obligation.rejected",
                            Map.of("reason", "snippet_not_grounded",
                                    "subject", obl.getSubject() != null ? obl.getSubject() : "",
                                    "snippet_preview", preview != null ? preview : ""));
                    continue;
                }

                result.add(obl);
            } catch (Exception e) {
                log.warn("Failed to parse obligation node: {}", e.getMessage());
            }
        }
        return result;
    }

    private static boolean isGrounded(String snippet, String chunkText) {
        if (snippet == null || snippet.isBlank() || chunkText == null) return false;
        String n = normalize(snippet);
        if (n.isEmpty()) return false;
        String needle = n.length() > 30 ? n.substring(0, 30) : n;
        return normalize(chunkText).contains(needle);
    }

    private static String normalize(String s) {
        return s == null ? "" : s.toLowerCase().replaceAll("\\s+", " ").trim();
    }

    private com.bunq.javabackend.model.enums.DeonticOperator parseEnum(String raw) {
        if (raw == null) return null;
        return switch (raw.trim()) {
            case "[O]", "O" -> com.bunq.javabackend.model.enums.DeonticOperator.O;
            case "[F]", "F" -> com.bunq.javabackend.model.enums.DeonticOperator.F;
            case "[P]", "P" -> com.bunq.javabackend.model.enums.DeonticOperator.P;
            default -> null;
        };
    }
}
