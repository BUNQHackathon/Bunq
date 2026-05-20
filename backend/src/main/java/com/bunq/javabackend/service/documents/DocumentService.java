package com.bunq.javabackend.service.documents;

import com.bunq.javabackend.dto.request.DocumentFinalizeRequest;
import com.bunq.javabackend.dto.request.DocumentPresignRequest;
import com.bunq.javabackend.dto.response.DocumentFinalizeResponse;
import com.bunq.javabackend.dto.response.DocumentListResponse;
import com.bunq.javabackend.dto.response.DocumentPresignResponse;
import com.bunq.javabackend.dto.response.DocumentResponseDTO;
import com.bunq.javabackend.dto.response.DocumentSummaryDTO;
import com.bunq.javabackend.exception.NotFoundException;
import com.bunq.javabackend.helper.S3PresignHelper;
import com.bunq.javabackend.model.document.Document;
import com.bunq.javabackend.model.control.Control;
import com.bunq.javabackend.model.obligation.Obligation;
import com.bunq.javabackend.repository.DocJurisdictionRepository;
import com.bunq.javabackend.repository.DocumentRepository;
import com.bunq.javabackend.repository.ControlRepository;
import com.bunq.javabackend.repository.ObligationRepository;
import com.bunq.javabackend.repository.SessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.util.Objects;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ChecksumMode;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentService {

    private static final Set<String> ALLOWED_KINDS = Set.of("regulation", "policy", "control");

    private final DocumentRepository documentRepository;
    private final DocJurisdictionRepository docJurisdictionRepository;
    private final SessionRepository sessionRepository;
    private final ObligationRepository obligationRepository;
    private final ControlRepository controlRepository;
    private final S3PresignHelper s3PresignHelper;
    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final KnowledgeBaseIngestionService knowledgeBaseIngestionService;

    @Value("${aws.s3.uploads-bucket}")
    private String uploadsBucket;

    public DocumentPresignResponse presign(DocumentPresignRequest req) {
        S3PresignHelper.DocumentPresignResult result =
                s3PresignHelper.presignDocumentUpload(req.getFilename(), req.getContentType(), req.getSha256());

        return DocumentPresignResponse.builder()
                .incomingKey(result.incomingKey())
                .uploadUrl(result.uploadUrl())
                .expiresInSeconds(result.expiresInSeconds())
                .build();
    }

    public DocumentFinalizeResponse finalize(DocumentFinalizeRequest req) {
        String kind = normalizeKind(req.getKind());
        HeadObjectResponse head;
        try {
            head = s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(uploadsBucket)
                    .key(req.getIncomingKey())
                    .checksumMode(ChecksumMode.ENABLED)
                    .build());
        } catch (NoSuchKeyException e) {
            throw new NotFoundException("Incoming object not found: " + req.getIncomingKey());
        }

        String checksumBase64 = head.checksumSHA256();
        if (checksumBase64 == null) {
            // Object existed before checksum support — no presign path
            throw new IllegalStateException("No SHA-256 checksum on object: " + req.getIncomingKey());
        }

        byte[] hashBytes = Base64.getDecoder().decode(checksumBase64);
        String hash = HexFormat.of().formatHex(hashBytes);

        Instant now = Instant.now();
        Set<String> jurisdictions = (req.getJurisdictions() == null || req.getJurisdictions().isEmpty()) ? Set.of("EU") : req.getJurisdictions();

        Optional<Document> existing = documentRepository.findById(hash);
        if (existing.isPresent()) {
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(uploadsBucket)
                    .key(req.getIncomingKey())
                    .build());
            Document updated = documentRepository
                    .updateUploadMetadata(hash, kind, jurisdictions, req.getDisplayName(), now)
                    .orElse(existing.get());
            docJurisdictionRepository.putAll(hash, jurisdictions, updated);
            knowledgeBaseIngestionService.publish(updated, existing.get().getKind());
            return DocumentFinalizeResponse.builder()
                    .document(toResponseDTO(updated))
                    .deduped(true)
                    .build();
        }

        String rawExt = req.getFilename().contains(".")
                ? req.getFilename().substring(req.getFilename().lastIndexOf('.') + 1)
                : "bin";
        String ext = rawExt.replaceAll("[^A-Za-z0-9]", "");
        if (ext.isEmpty() || ext.length() > 16) ext = "bin";
        String destKey = "documents/" + hash + "." + ext;
        long sizeBytes = head.contentLength() != null ? head.contentLength() : 0L;

        s3Client.copyObject(CopyObjectRequest.builder()
                .sourceBucket(uploadsBucket)
                .sourceKey(req.getIncomingKey())
                .destinationBucket(uploadsBucket)
                .destinationKey(destKey)
                .build());

        s3Client.deleteObject(DeleteObjectRequest.builder()
                .bucket(uploadsBucket)
                .key(req.getIncomingKey())
                .build());

        Document doc = Document.builder()
                .id(hash)
                .filename(req.getFilename())
                .contentType(req.getContentType())
                .sizeBytes(sizeBytes)
                .s3Key(destKey)
                .kind(kind)
                .jurisdictions(jurisdictions)
                .firstSeenAt(now)
                .lastUsedAt(now)
                .displayName(req.getDisplayName())
                .obligationsExtracted(false)
                .controlsExtracted(false)
                .build();

        try {
            documentRepository.saveIfNotExists(doc);
        } catch (ConditionalCheckFailedException e) {
            // Race: another request saved it first — treat as dedup
            Document existing2 = documentRepository.findById(hash).orElse(doc);
            if (existing2 != null && !Objects.equals(existing2.getS3Key(), destKey)) {
                // Winner used a different extension — clean up our orphan copy
                try {
                    s3Client.deleteObject(DeleteObjectRequest.builder()
                            .bucket(uploadsBucket)
                            .key(destKey)
                            .build());
                } catch (Exception ex) {
                    log.warn("Failed to delete orphan S3 object {}: {}", destKey, ex.getMessage());
                }
            }
            Document updated = documentRepository
                    .updateUploadMetadata(hash, kind, jurisdictions, req.getDisplayName(), now)
                    .orElse(existing2);
            docJurisdictionRepository.putAll(hash, jurisdictions, updated);
            knowledgeBaseIngestionService.publish(updated, existing2.getKind());
            return DocumentFinalizeResponse.builder()
                    .document(toResponseDTO(updated))
                    .deduped(true)
                    .build();
        }

        docJurisdictionRepository.putAll(hash, doc.getJurisdictions(), doc);
        knowledgeBaseIngestionService.publish(doc, null);

        return DocumentFinalizeResponse.builder()
                .document(toResponseDTO(doc))
                .deduped(false)
                .build();
    }

    private static String normalizeKind(String kind) {
        String normalized = kind == null ? "" : kind.trim().toLowerCase();
        if (!ALLOWED_KINDS.contains(normalized)) {
            throw new IllegalArgumentException("Unsupported document kind: " + kind);
        }
        return normalized;
    }

    public DocumentListResponse list(String kind, int limit) {
        List<Document> docs = kind != null
                ? documentRepository.findByKind(kind, limit)
                : documentRepository.scanAll(limit);

        List<DocumentSummaryDTO> summaries = docs.stream()
                .map(DocumentService::toSummaryDTO)
                .toList();

        return DocumentListResponse.builder()
                .documents(summaries)
                .nextCursor(null)
                .build();
    }

    public DocumentResponseDTO get(String id) {
        return documentRepository.findById(id)
                .map(this::toResponseDTO)
                .orElseThrow(() -> new NotFoundException("Document not found: " + id));
    }

    public void delete(String id) {
        Document document = documentRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Document not found: " + id));

        deleteS3Object(uploadsBucket, document.getS3Key());
        deleteS3Object(uploadsBucket, document.getExtractionS3Key());
        knowledgeBaseIngestionService.delete(document);
        docJurisdictionRepository.deleteAll(id, document.getJurisdictions());
        sessionRepository.detachDocumentFromAll(id);
        obligationRepository.findByDocumentId(id).stream()
                .map(Obligation::getId)
                .forEach(obligationRepository::deleteById);
        controlRepository.findByDocumentId(id).stream()
                .map(Control::getId)
                .forEach(controlRepository::deleteById);
        documentRepository.deleteById(id);
    }

    private void deleteS3Object(String bucket, String key) {
        if (bucket == null || bucket.isBlank() || key == null || key.isBlank()) {
            return;
        }
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build());
        } catch (Exception ex) {
            log.warn("Failed to delete S3 object s3://{}/{}: {}", bucket, key, ex.getMessage());
        }
    }

    private DocumentResponseDTO toResponseDTO(Document doc) {
        return DocumentResponseDTO.builder()
                .id(doc.getId())
                .filename(doc.getFilename())
                .displayName(doc.getDisplayName())
                .contentType(doc.getContentType())
                .sizeBytes(doc.getSizeBytes())
                .kind(doc.getKind())
                .jurisdictions(doc.getJurisdictions())
                .firstSeenAt(doc.getFirstSeenAt())
                .lastUsedAt(doc.getLastUsedAt())
                .extractedText(doc.getExtractedText())
                .extractedAt(doc.getExtractedAt())
                .pageCount(doc.getPageCount())
                .obligationsExtracted(doc.isObligationsExtracted())
                .controlsExtracted(doc.isControlsExtracted())
                .downloadUrl(doc.getS3Key() != null ? presignGetUrl(doc.getS3Key()) : null)
                .build();
    }

    private String presignGetUrl(String key) {
        try {
            GetObjectRequest req = GetObjectRequest.builder().bucket(uploadsBucket).key(key).build();
            GetObjectPresignRequest presignReq = GetObjectPresignRequest.builder()
                    .signatureDuration(java.time.Duration.ofMinutes(15))
                    .getObjectRequest(req)
                    .build();
            return s3Presigner.presignGetObject(presignReq).url().toString();
        } catch (Exception e) {
            log.warn("Failed to presign GET URL for key {}: {}", key, e.getMessage());
            return null;
        }
    }

    private static DocumentSummaryDTO toSummaryDTO(Document doc) {
        return DocumentSummaryDTO.builder()
                .id(doc.getId())
                .filename(doc.getFilename())
                .displayName(doc.getDisplayName())
                .contentType(doc.getContentType())
                .sizeBytes(doc.getSizeBytes())
                .kind(doc.getKind())
                .jurisdictions(doc.getJurisdictions())
                .firstSeenAt(doc.getFirstSeenAt())
                .lastUsedAt(doc.getLastUsedAt())
                .extractedAt(doc.getExtractedAt())
                .pageCount(doc.getPageCount())
                .obligationsExtracted(doc.isObligationsExtracted())
                .controlsExtracted(doc.isControlsExtracted())
                .build();
    }
}
