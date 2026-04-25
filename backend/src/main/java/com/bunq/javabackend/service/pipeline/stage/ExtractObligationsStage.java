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
import com.bunq.javabackend.service.BedrockService;
import com.bunq.javabackend.service.bedrock.ToolDefinitions;
import com.bunq.javabackend.service.pipeline.PipelineContext;
import com.bunq.javabackend.service.pipeline.PipelineStage;
import com.bunq.javabackend.service.pipeline.Stage;
import com.bunq.javabackend.service.pipeline.prompts.SystemPrompts;
import com.bunq.javabackend.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExtractObligationsStage implements Stage {

    private final BedrockService bedrockService;
    private final ObligationRepository obligationRepository;
    private final DocumentRepository documentRepository;
    private final SessionRepository sessionRepository;
    private final ObjectMapper objectMapper;
    private final S3Client s3Client;

    @Value("${aws.s3.uploads-bucket}")
    private String uploadsBucket;

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
                runBedrockExtraction(ctx, regulation, null);
                return;
            }

            for (String docId : regulationDocIds) {
                Document doc = documentRepository.findById(docId).orElse(null);
                if (doc == null) {
                    log.warn("Document {} not found in library; skipping", docId);
                    continue;
                }

                if (doc.isObligationsExtracted()) {
                    // Cache hit — clone existing obligations into this session
                    List<Obligation> originals = obligationRepository.findByDocumentId(doc.getId());
                    log.info("Cache hit for document {} ({} obligations); cloning into session {}",
                            doc.getId(), originals.size(), ctx.getSessionId());

                    for (Obligation original : originals) {
                        Obligation clone = cloneObligation(original, ctx.getSessionId());
                        obligationRepository.save(clone);
                        ctx.getObligations().add(clone);
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
                    runBedrockExtraction(ctx, textToExtract, doc);
                }
            }
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

    private void runBedrockExtraction(PipelineContext ctx, String text, Document doc) {
        Map<String, String> userInput = Map.of(
                "regulation_text", text,
                "regulation_id", "REG-" + ctx.getSessionId(),
                "article", "general",
                "paragraph_id", "1"
        );

        JsonNode toolInput = bedrockService.invokeModelWithTool(
                BedrockModel.SONNET.getModelId(),
                SystemPrompts.EXTRACT_OBLIGATIONS,
                userInput,
                ToolDefinitions.EXTRACT_OBLIGATIONS_TOOL
        );

        String documentId = doc != null ? doc.getId() : null;
        List<Obligation> extracted = parseObligations(toolInput, ctx.getSessionId(), documentId);

        for (Obligation obl : extracted) {
            obligationRepository.save(obl);
            ctx.getObligations().add(obl);
            ctx.getSseEmitterService().send(ctx.getSessionId(), "obligation.extracted",
                    ObligationMapper.toDto(obl));
        }

        if (doc != null) {
            doc.setObligationsExtracted(true);
            documentRepository.save(doc);
        }

        log.info("ExtractObligationsStage: extracted {} obligations for session {} (document={})",
                extracted.size(), ctx.getSessionId(), documentId);
    }

    private Obligation cloneObligation(Obligation original, String sessionId) {
        Obligation clone = new Obligation();
        clone.setId(IdGenerator.generateObligationId());
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

    private List<Obligation> parseObligations(JsonNode toolInput, String sessionId, String documentId) {
        List<Obligation> result = new ArrayList<>();
        if (toolInput == null || toolInput.isMissingNode()) return result;

        // Tool output may be an object with an "obligations" array, or a direct array
        JsonNode arrayNode = toolInput.isArray() ? toolInput : toolInput.path("obligations");
        if (!arrayNode.isArray()) return result;

        for (JsonNode node : arrayNode) {
            try {
                Obligation obl = new Obligation();
                obl.setId(IdGenerator.generateObligationId());
                obl.setSessionId(sessionId);
                obl.setDocumentId(documentId);
                obl.setDeontic(parseEnum(node.path("deontic").asText()));
                obl.setSubject(node.path("subject").asText(null));
                obl.setAction(node.path("action").asText(null));
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
                source.setSourceText(node.path("source_text_snippet").asText(null));
                obl.setSource(source);

                result.add(obl);
            } catch (Exception e) {
                log.warn("Failed to parse obligation node: {}", e.getMessage());
            }
        }
        return result;
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
