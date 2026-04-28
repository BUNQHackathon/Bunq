package com.bunq.javabackend.service.pipeline.stage;

import com.bunq.javabackend.model.document.Document;
import com.bunq.javabackend.model.session.Session;
import com.bunq.javabackend.repository.DocumentRepository;
import com.bunq.javabackend.repository.SessionRepository;
import com.bunq.javabackend.service.documents.TextractAsyncService;
import com.bunq.javabackend.service.documents.TranscribeAsyncService;
import com.bunq.javabackend.service.pipeline.IngestedDocument;
import com.bunq.javabackend.service.pipeline.PipelineContext;
import com.bunq.javabackend.service.pipeline.PipelineStage;
import com.bunq.javabackend.service.pipeline.Stage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class IngestStage implements Stage {

    private static final long MAX_PLAIN_TEXT_BYTES = 5 * 1024 * 1024L; // 5 MB

    /** Dedup map: prevents two concurrent sessions from double-extracting the same doc. */
    private final ConcurrentHashMap<String, CompletableFuture<String>> inFlightExtractions = new ConcurrentHashMap<>();

    private final SessionRepository sessionRepository;
    private final DocumentRepository documentRepository;
    private final TextractAsyncService textractAsyncService;
    private final TranscribeAsyncService transcribeAsyncService;
    private final S3Client s3Client;

    @Value("${aws.s3.uploads-bucket}")
    private String uploadsBucket;

    @Override
    public PipelineStage stage() {
        return PipelineStage.INGEST;
    }

    @Override
    public CompletableFuture<Void> execute(PipelineContext ctx) {
        return CompletableFuture.runAsync(() -> {
            List<String> documentIds = sessionRepository.findById(ctx.getSessionId())
                    .map(Session::getDocumentIds)
                    .orElse(List.of());

            if (documentIds.isEmpty()) {
                log.info("No documentIds found for session {}, ingest produces no chunks", ctx.getSessionId());
                return;
            }

            Instant now = Instant.now();
            List<IngestedDocument> ingestedDocuments = new ArrayList<>();

            for (String docId : documentIds) {
                Document doc = documentRepository.findById(docId)
                        .orElseThrow(() -> new IllegalStateException("Document not found: " + docId));

                // Non-critical: update lastUsedAt
                try {
                    documentRepository.touchLastUsed(docId, now);
                } catch (Exception e) {
                    log.warn("touchLastUsed failed for document {}: {}", docId, e.getMessage());
                }

                String extractedText;

                if (doc.getExtractedText() != null || doc.getExtractionS3Key() != null) {
                    // Cache hit — text stored on Document row or in S3
                    if (doc.getExtractedText() != null) {
                        extractedText = doc.getExtractedText();
                    } else {
                        extractedText = s3Client.getObjectAsBytes(
                                GetObjectRequest.builder()
                                        .bucket(uploadsBucket)
                                        .key(doc.getExtractionS3Key())
                                        .build())
                                .asUtf8String();
                    }
                    log.info("Cache hit for document {} (kind={})", docId, doc.getKind());

                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("documentId", docId);
                    payload.put("kind", doc.getKind());
                    payload.put("recordsReused", 0);
                    ctx.getSseEmitterService().send(ctx.getSessionId(), "document.cached", payload);

                } else if (containsIgnoreCase(doc.getContentType(), "pdf")) {
                    // Textract path — deduped so concurrent sessions share one job per docId
                    final String finalDocId = docId;
                    CompletableFuture<String> future = inFlightExtractions.computeIfAbsent(docId, id -> {
                        CompletableFuture<String> f = CompletableFuture.supplyAsync(() -> {
                            try {
                                log.info("Running Textract for document {} s3Key={}", finalDocId, doc.getS3Key());
                                String text = textractAsyncService.extractText(uploadsBucket, doc.getS3Key(), ctx);
                                Integer pageCount = estimatePageCount(text);
                                String extractionKey = "extractions/" + finalDocId + ".txt";
                                s3Client.putObject(PutObjectRequest.builder()
                                                .bucket(uploadsBucket)
                                                .key(extractionKey)
                                                .contentType("text/plain; charset=utf-8")
                                                .build(),
                                        RequestBody.fromString(text, StandardCharsets.UTF_8));
                                log.info("Stored extracted text in S3 for document {}: key={} size={} chars", finalDocId, extractionKey, text.length());
                                doc.setExtractionS3Key(extractionKey);
                                doc.setExtractedAt(now);
                                doc.setPageCount(pageCount);
                                documentRepository.save(doc);
                                return text;
                            } catch (Exception e) {
                                throw new CompletionException(e);
                            }
                        });
                        f.whenComplete((r, ex) -> inFlightExtractions.remove(finalDocId));
                        return f;
                    });
                    extractedText = future.join();

                    Integer pageCount = estimatePageCount(extractedText);
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("documentId", docId);
                    payload.put("kind", doc.getKind());
                    payload.put("pageCount", pageCount);
                    payload.put("extractedAt", now.toString());
                    ctx.getSseEmitterService().send(ctx.getSessionId(), "document.extracted", payload);

                } else if (containsIgnoreCase(doc.getContentType(), "audio")) {
                    // Transcribe path — deduped so concurrent sessions share one job per docId
                    final String finalDocId = docId;
                    CompletableFuture<String> future = inFlightExtractions.computeIfAbsent(docId, id -> {
                        CompletableFuture<String> f = CompletableFuture.supplyAsync(() -> {
                            try {
                                log.info("Running Transcribe for document {} s3Key={}", finalDocId, doc.getS3Key());
                                String text;
                                try {
                                    text = transcribeAsyncService.transcribeAudio(uploadsBucket, doc.getS3Key(), ctx);
                                } catch (Exception e) {
                                    log.warn("Transcribe failed for document {}: {}", finalDocId, e.getMessage());
                                    text = "";
                                }
                                String audioExtractionKey = "extractions/" + finalDocId + ".txt";
                                s3Client.putObject(PutObjectRequest.builder()
                                                .bucket(uploadsBucket)
                                                .key(audioExtractionKey)
                                                .contentType("text/plain; charset=utf-8")
                                                .build(),
                                        RequestBody.fromString(text, StandardCharsets.UTF_8));
                                log.info("Stored extracted text in S3 for document {}: key={} size={} chars", finalDocId, audioExtractionKey, text.length());
                                doc.setExtractionS3Key(audioExtractionKey);
                                doc.setExtractedAt(now);
                                doc.setPageCount(null);
                                documentRepository.save(doc);
                                return text;
                            } catch (Exception e) {
                                throw new CompletionException(e);
                            }
                        });
                        f.whenComplete((r, ex) -> inFlightExtractions.remove(finalDocId));
                        return f;
                    });
                    extractedText = future.join();

                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("documentId", docId);
                    payload.put("kind", doc.getKind());
                    payload.put("pageCount", (Object) null);
                    payload.put("extractedAt", now.toString());
                    ctx.getSseEmitterService().send(ctx.getSessionId(), "document.extracted", payload);

                } else {
                    // Plain text / JSON / other — download bytes from S3 (5 MB guard) — deduped per docId
                    if (doc.getSizeBytes() != null && doc.getSizeBytes() > MAX_PLAIN_TEXT_BYTES) {
                        log.warn("Skipping S3 download for document {} — sizeBytes={} exceeds 5 MB limit",
                                docId, doc.getSizeBytes());
                        extractedText = "";
                    } else {
                        final String finalDocId = docId;
                        CompletableFuture<String> future = inFlightExtractions.computeIfAbsent(docId, id -> {
                            CompletableFuture<String> f = CompletableFuture.supplyAsync(() -> {
                                try {
                                    byte[] bytes = s3Client.getObjectAsBytes(
                                            GetObjectRequest.builder()
                                                    .bucket(uploadsBucket)
                                                    .key(doc.getS3Key())
                                                    .build())
                                            .asByteArray();
                                    String text = new String(bytes, StandardCharsets.UTF_8);
                                    String plainExtractionKey = "extractions/" + finalDocId + ".txt";
                                    s3Client.putObject(PutObjectRequest.builder()
                                                    .bucket(uploadsBucket)
                                                    .key(plainExtractionKey)
                                                    .contentType("text/plain; charset=utf-8")
                                                    .build(),
                                            RequestBody.fromString(text, StandardCharsets.UTF_8));
                                    log.info("Stored extracted text in S3 for document {}: key={} size={} chars", finalDocId, plainExtractionKey, text.length());
                                    doc.setExtractionS3Key(plainExtractionKey);
                                    doc.setExtractedAt(now);
                                    documentRepository.save(doc);
                                    return text;
                                } catch (Exception e) {
                                    throw new CompletionException(e);
                                }
                            });
                            f.whenComplete((r, ex) -> inFlightExtractions.remove(finalDocId));
                            return f;
                        });
                        extractedText = future.join();

                        Map<String, Object> payload = new LinkedHashMap<>();
                        payload.put("documentId", docId);
                        payload.put("kind", doc.getKind());
                        payload.put("pageCount", (Object) null);
                        payload.put("extractedAt", now.toString());
                        ctx.getSseEmitterService().send(ctx.getSessionId(), "document.extracted", payload);
                    }
                }

                ingestedDocuments.add(new IngestedDocument(docId, doc.getKind(), extractedText));
            }

            ctx.setIngestedDocuments(ingestedDocuments);

            // Populate ctx fields consumed by downstream stages from document kinds
            String regulation = joinTextByKind(ingestedDocuments, "regulation");
            String policy     = joinTextByKind(ingestedDocuments, "policy");
            String brief      = joinTextByKind(ingestedDocuments, "brief");

            if (!regulation.isBlank()) {
                ctx.setRegulation(regulation);
            }
            if (!policy.isBlank()) {
                ctx.setPolicy(policy);
            }
            if (!brief.isBlank()) {
                ctx.setBriefText(brief);
            }

            log.info("IngestStage complete for session {}: {} documents ingested, regulation={} chars, policy={} chars, brief={} chars",
                    ctx.getSessionId(), ingestedDocuments.size(),
                    regulation.length(), policy.length(), brief.length());
        });
    }

    /** Rough page estimate: form-feed chars first, else length / 3000. */
    private Integer estimatePageCount(String text) {
        if (text == null || text.isEmpty()) return null;
        long ffCount = text.chars().filter(c -> c == '\f').count();
        if (ffCount > 0) return (int) (ffCount + 1);
        int estimate = text.length() / 3000;
        return estimate > 0 ? estimate : null;
    }

    private String joinTextByKind(List<IngestedDocument> docs, String kind) {
        return docs.stream()
                .filter(d -> kind.equalsIgnoreCase(d.kind()))
                .map(IngestedDocument::text)
                .collect(Collectors.joining("\n\n"));
    }

    private boolean containsIgnoreCase(String value, String search) {
        return value != null && value.toLowerCase().contains(search.toLowerCase());
    }
}
