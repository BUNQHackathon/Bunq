package com.bunq.javabackend.service.documents;

import com.bunq.javabackend.config.KnowledgeBaseConfig;
import com.bunq.javabackend.model.document.Document;
import com.bunq.javabackend.model.enums.KbType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.bedrockagent.BedrockAgentClient;
import software.amazon.awssdk.services.bedrockagent.model.ConflictException;
import software.amazon.awssdk.services.bedrockagent.model.GetIngestionJobRequest;
import software.amazon.awssdk.services.bedrockagent.model.GetIngestionJobResponse;
import software.amazon.awssdk.services.bedrockagent.model.IngestionJobStatus;
import software.amazon.awssdk.services.bedrockagent.model.StartIngestionJobRequest;
import software.amazon.awssdk.services.bedrockagent.model.StartIngestionJobResponse;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class KnowledgeBaseIngestionService {

    private final S3Client s3Client;
    private final BedrockAgentClient bedrockAgentClient;
    private final KnowledgeBaseConfig knowledgeBaseConfig;
    private final ObjectMapper objectMapper;

    @Value("${aws.s3.uploads-bucket}")
    private String uploadsBucket;

    public void publish(Document document, String previousKind) {
        if (document == null || !hasText(document.getS3Key())) {
            return;
        }

        KbType targetType = kbTypeForKind(document.getKind());
        if (targetType == null) {
            log.warn("Skipping KB publish for document {} with unsupported kind {}", document.getId(), document.getKind());
            return;
        }

        KbType previousType = kbTypeForKind(previousKind);
        if (previousType != null && previousType != targetType) {
            knowledgeBaseConfig.findByKbType(previousType)
                    .ifPresent(entry -> removeFromSourceBucket(entry, document.getS3Key(), document.getId()));
        }

        knowledgeBaseConfig.findByKbType(targetType)
                .ifPresentOrElse(
                        entry -> publishToEntry(entry, document),
                        () -> log.warn("Skipping KB publish for document {}: no KB configured for {}", document.getId(), targetType));
    }

    public void delete(Document document) {
        if (document == null || !hasText(document.getS3Key())) {
            return;
        }
        for (KnowledgeBaseConfig.Entry entry : knowledgeBaseConfig.getConfiguredEntries()) {
            removeFromSourceBucket(entry, document.getS3Key(), document.getId());
        }
    }

    private void publishToEntry(KnowledgeBaseConfig.Entry entry, Document document) {
        if (!hasText(entry.getSourceBucket()) || !hasText(entry.getDataSourceId()) || !hasText(entry.getKnowledgeBaseId())) {
            log.warn("Skipping KB publish for document {}: {} is missing sourceBucket/dataSourceId/knowledgeBaseId",
                    document.getId(), entry.getKey());
            return;
        }

        try {
            String destinationKey = document.getS3Key();
            s3Client.copyObject(CopyObjectRequest.builder()
                    .sourceBucket(uploadsBucket)
                    .sourceKey(document.getS3Key())
                    .destinationBucket(entry.getSourceBucket())
                    .destinationKey(destinationKey)
                    .build());

            putMetadata(entry.getSourceBucket(), destinationKey, document);
            startIngestion(entry, document.getId());
        } catch (Exception ex) {
            log.warn("Failed to publish document {} to KB {}: {}", document.getId(), entry.getKey(), ex.getMessage(), ex);
        }
    }

    private void removeFromSourceBucket(KnowledgeBaseConfig.Entry entry, String sourceKey, String documentId) {
        if (!hasText(entry.getSourceBucket())) {
            return;
        }
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(entry.getSourceBucket())
                    .key(sourceKey)
                    .build());
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(entry.getSourceBucket())
                    .key(metadataKey(sourceKey))
                    .build());
            startIngestion(entry, documentId);
        } catch (Exception ex) {
            log.warn("Failed to remove document {} from old KB {}: {}", documentId, entry.getKey(), ex.getMessage(), ex);
        }
    }

    private void putMetadata(String bucket, String documentKey, Document document) throws Exception {
        String metadataJson = objectMapper.writeValueAsString(buildMetadata(document));
        s3Client.putObject(PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(metadataKey(documentKey))
                        .contentType("application/json")
                        .build(),
                RequestBody.fromString(metadataJson));
    }

    private Map<String, Object> buildMetadata(Document document) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("document_id", stringAttribute(document.getId(), false));
        attributes.put("filename", stringAttribute(coalesce(document.getDisplayName(), document.getFilename()), true));
        attributes.put("kind", stringAttribute(document.getKind(), true));
        attributes.put("jurisdictions", stringListAttribute(normalizedJurisdictions(document.getJurisdictions()), false));
        return Map.of("metadataAttributes", attributes);
    }

    private static Map<String, Object> stringAttribute(String value, boolean includeForEmbedding) {
        return Map.of(
                "value", Map.of(
                        "type", "STRING",
                        "stringValue", value != null ? value : ""),
                "includeForEmbedding", includeForEmbedding);
    }

    private static Map<String, Object> stringListAttribute(List<String> values, boolean includeForEmbedding) {
        return Map.of(
                "value", Map.of(
                        "type", "STRING_LIST",
                        "stringListValue", values.isEmpty() ? List.of("EU") : values),
                "includeForEmbedding", includeForEmbedding);
    }

    private void startIngestion(KnowledgeBaseConfig.Entry entry, String documentId) {
        if (!hasText(entry.getKnowledgeBaseId()) || !hasText(entry.getDataSourceId())) {
            return;
        }
        try {
            StartIngestionJobResponse response = bedrockAgentClient.startIngestionJob(StartIngestionJobRequest.builder()
                    .knowledgeBaseId(entry.getKnowledgeBaseId())
                    .dataSourceId(entry.getDataSourceId())
                    .description("Document library sync for " + documentId)
                    .build());
            String ingestionJobId = response.ingestionJob() != null ? response.ingestionJob().ingestionJobId() : null;
            log.info("Started KB ingestion for document {} on {} job={}", documentId, entry.getKey(), ingestionJobId);
            if (ingestionJobId != null) {
                trackIngestionJob(entry, documentId, ingestionJobId);
            }
        } catch (ConflictException ex) {
            log.info("KB ingestion already running for {} while publishing document {} — retrying after delay", entry.getKey(), documentId);
            Thread.ofVirtual().start(() -> {
                try {
                    Thread.sleep(30_000);
                    StartIngestionJobResponse retry = bedrockAgentClient.startIngestionJob(StartIngestionJobRequest.builder()
                            .knowledgeBaseId(entry.getKnowledgeBaseId())
                            .dataSourceId(entry.getDataSourceId())
                            .description("Document library sync retry for " + documentId)
                            .build());
                    String retryJobId = retry.ingestionJob() != null ? retry.ingestionJob().ingestionJobId() : null;
                    log.info("Retry KB ingestion started for document {} on {} job={}", documentId, entry.getKey(), retryJobId);
                    if (retryJobId != null) {
                        trackIngestionJob(entry, documentId, retryJobId);
                    }
                } catch (Exception retryEx) {
                    log.warn("KB ingestion retry failed for document {} on {}: {}", documentId, entry.getKey(), retryEx.getMessage());
                }
            });
        }
    }

    private void trackIngestionJob(KnowledgeBaseConfig.Entry entry, String documentId, String jobId) {
        Thread.ofVirtual().start(() -> {
            long pollIntervalMs = 15_000;
            long deadlineMs = System.currentTimeMillis() + 10 * 60 * 1_000L;
            while (System.currentTimeMillis() < deadlineMs) {
                try {
                    Thread.sleep(pollIntervalMs);
                    GetIngestionJobResponse resp = bedrockAgentClient.getIngestionJob(
                            GetIngestionJobRequest.builder()
                                    .knowledgeBaseId(entry.getKnowledgeBaseId())
                                    .dataSourceId(entry.getDataSourceId())
                                    .ingestionJobId(jobId)
                                    .build());
                    IngestionJobStatus status = resp.ingestionJob() != null ? resp.ingestionJob().status() : null;
                    if (status == IngestionJobStatus.COMPLETE) {
                        log.info("KB ingestion COMPLETE for document {} on {} job={}", documentId, entry.getKey(), jobId);
                        return;
                    } else if (status == IngestionJobStatus.FAILED) {
                        log.error("KB ingestion FAILED for document {} on {} job={} stats={}",
                                documentId, entry.getKey(), jobId,
                                resp.ingestionJob().statistics());
                        return;
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception ex) {
                    log.warn("KB ingestion poll error for document {} on {} job={}: {}", documentId, entry.getKey(), jobId, ex.getMessage());
                }
            }
            log.warn("KB ingestion tracking timed out for document {} on {} job={}", documentId, entry.getKey(), jobId);
        });
    }

    private static String metadataKey(String documentKey) {
        return documentKey + ".metadata.json";
    }

    private static List<String> normalizedJurisdictions(Set<String> jurisdictions) {
        if (jurisdictions == null || jurisdictions.isEmpty()) {
            return List.of("EU");
        }
        List<String> normalized = new ArrayList<>();
        jurisdictions.stream()
                .filter(Objects::nonNull)
                .map(value -> value.trim().toUpperCase(Locale.ROOT))
                .filter(value -> !value.isBlank())
                .sorted()
                .forEach(normalized::add);
        return normalized.isEmpty() ? List.of("EU") : normalized;
    }

    private static KbType kbTypeForKind(String kind) {
        if (kind == null) {
            return null;
        }
        return switch (kind.trim().toLowerCase(Locale.ROOT)) {
            case "regulation" -> KbType.REGULATIONS;
            case "policy" -> KbType.POLICIES;
            case "control" -> KbType.CONTROLS;
            default -> null;
        };
    }

    private static String coalesce(String first, String second) {
        return hasText(first) ? first : second;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
