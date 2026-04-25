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
import com.bunq.javabackend.service.BedrockService;
import com.bunq.javabackend.service.bedrock.ToolDefinitions;
import com.bunq.javabackend.service.pipeline.PipelineContext;
import com.bunq.javabackend.service.pipeline.PipelineStage;
import com.bunq.javabackend.service.pipeline.Stage;
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
                                @Qualifier("pipelineExecutor") Executor pipelineExecutor) {
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
        return CompletableFuture.runAsync(() -> {
            String policyText = ctx.getPolicy();
            if (policyText == null || policyText.isBlank()) {
                ctx.getSseEmitterService().send(ctx.getSessionId(), "stage.skipped",
                        Map.of("stage", PipelineStage.EXTRACT_CONTROLS, "reason", "no policy text available"));
                log.info("ExtractControlsStage: no policy text for session {}, skipping", ctx.getSessionId());
                return;
            }

            List<String> documentIds = sessionRepository.findById(ctx.getSessionId())
                    .map(Session::getDocumentIds)
                    .orElse(List.of());

            List<String> policyDocIds = documentIds.stream()
                    .filter(id -> {
                        Optional<Document> doc = documentRepository.findById(id);
                        return doc.isPresent() && "policy".equals(doc.get().getKind());
                    })
                    .toList();

            if (policyDocIds.isEmpty()) {
                // No policy-kind documents attached — fall back to single Bedrock call on concatenated text
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

                        if (doc.isControlsExtracted()) {
                            // Cache hit — clone existing controls into this session
                            List<Control> originals = controlRepository.findByDocumentId(doc.getId());
                            log.info("Cache hit for document {} ({} controls); cloning into session {}",
                                    doc.getId(), originals.size(), ctx.getSessionId());

                            for (Control original : originals) {
                                Control clone = cloneControl(original, ctx.getSessionId());
                                controlRepository.save(clone);
                                collectedControls.add(clone);
                                ctx.getSseEmitterService().send(ctx.getSessionId(), "control.extracted",
                                        ControlMapper.toDto(clone));
                            }

                            ctx.getSseEmitterService().send(ctx.getSessionId(), "document.cached",
                                    Map.of("documentId", doc.getId(), "kind", "policy",
                                            "recordsReused", originals.size()));
                        } else {
                            // Cold path — Bedrock extraction; use per-doc text if available, else fall back to ctx.getPolicy()
                            String loaded = loadExtractedText(doc);
                            String textToExtract = (loaded != null && !loaded.isBlank())
                                    ? loaded
                                    : policyText;
                            log.info("Cold extraction for document {} in session {}", doc.getId(), ctx.getSessionId());
                            runBedrockExtraction(ctx, textToExtract, doc, collectedControls);
                        }
                    }, pipelineExecutor))
                    .toList();

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            ctx.getControls().addAll(collectedControls);
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

    private void runBedrockExtraction(PipelineContext ctx, String text, Document doc, List<Control> sink) {
        Map<String, String> userInput = Map.of("policy_text", text, "policy_id", "POL-" + ctx.getSessionId());

        JsonNode toolInput = bedrockService.invokeModelWithTool(
                BedrockModel.HAIKU.getModelId(),
                SystemPrompts.EXTRACT_CONTROLS,
                userInput,
                ToolDefinitions.EXTRACT_CONTROLS_TOOL
        );

        String documentId = doc != null ? doc.getId() : null;
        List<Control> extracted = parseControls(toolInput, ctx.getSessionId(), documentId);

        for (Control ctrl : extracted) {
            controlRepository.save(ctrl);
            sink.add(ctrl);
            ctx.getSseEmitterService().send(ctx.getSessionId(), "control.extracted",
                    ControlMapper.toDto(ctrl));
        }

        if (doc != null) {
            doc.setControlsExtracted(true);
            documentRepository.save(doc);
        }

        log.info("ExtractControlsStage: extracted {} controls for session {} (document={})",
                extracted.size(), ctx.getSessionId(), documentId);
    }

    private Control cloneControl(Control original, String sessionId) {
        Control clone = new Control();
        clone.setId(IdGenerator.generateControlId());
        clone.setSessionId(sessionId);
        clone.setDocumentId(original.getDocumentId());
        clone.setControlType(original.getControlType());
        clone.setCategory(original.getCategory());
        clone.setDescription(original.getDescription());
        clone.setOwner(original.getOwner());
        clone.setTestingCadence(original.getTestingCadence());
        clone.setEvidenceType(original.getEvidenceType());
        clone.setLastTested(original.getLastTested());
        clone.setTestingStatus(original.getTestingStatus());
        clone.setImplementationStatus(original.getImplementationStatus());
        clone.setMappedStandards(original.getMappedStandards());
        clone.setLinkedTools(original.getLinkedTools());
        clone.setSourceDocRef(original.getSourceDocRef());
        clone.setBankId(original.getBankId());
        return clone;
    }

    private List<Control> parseControls(JsonNode toolInput, String sessionId, String documentId) {
        List<Control> result = new ArrayList<>();
        if (toolInput == null || toolInput.isMissingNode()) return result;

        JsonNode arrayNode = toolInput.isArray() ? toolInput : toolInput.path("controls");
        if (!arrayNode.isArray()) return result;

        for (JsonNode node : arrayNode) {
            try {
                Control ctrl = new Control();
                ctrl.setId(IdGenerator.generateControlId());
                ctrl.setSessionId(sessionId);
                ctrl.setDocumentId(documentId);
                ctrl.setDescription(node.path("description").asText(null));
                ctrl.setOwner(node.path("owner").asText(null));

                String typeStr = node.path("control_type").asText(null);
                if (typeStr != null) {
                    try { ctrl.setControlType(ControlType.valueOf(typeStr.toUpperCase())); } catch (Exception ignored) {}
                }
                String catStr = node.path("category").asText(null);
                if (catStr != null) {
                    try { ctrl.setCategory(ControlCategory.valueOf(catStr.toUpperCase())); } catch (Exception ignored) {}
                }

                JsonNode standardsNode = node.path("mapped_standards");
                if (standardsNode.isArray()) {
                    ArrayList<String> standards = new ArrayList<String>();
                    standardsNode.forEach(s -> standards.add(s.asText()));
                    ctrl.setMappedStandards(standards);
                }

                result.add(ctrl);
            } catch (Exception e) {
                log.warn("Failed to parse control node: {}", e.getMessage());
            }
        }
        return result;
    }
}
