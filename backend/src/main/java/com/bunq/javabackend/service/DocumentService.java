package com.bunq.javabackend.service;

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
import com.bunq.javabackend.repository.DocJurisdictionRepository;
import com.bunq.javabackend.repository.DocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ChecksumMode;
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

    private final DocumentRepository documentRepository;
    private final DocJurisdictionRepository docJurisdictionRepository;
    private final S3PresignHelper s3PresignHelper;
    private final S3Client s3Client;

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

        Optional<Document> existing = documentRepository.findById(hash);
        if (existing.isPresent()) {
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(uploadsBucket)
                    .key(req.getIncomingKey())
                    .build());
            documentRepository.touchLastUsed(hash, Instant.now());
            return DocumentFinalizeResponse.builder()
                    .document(toResponseDTO(existing.get()))
                    .deduped(true)
                    .build();
        }

        String ext = req.getFilename().contains(".")
                ? req.getFilename().substring(req.getFilename().lastIndexOf('.') + 1)
                : "bin";
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

        Instant now = Instant.now();
        Set<String> jurisdictions = (req.getJurisdictions() == null || req.getJurisdictions().isEmpty()) ? Set.of("EU") : req.getJurisdictions();
        Document doc = Document.builder()
                .id(hash)
                .filename(req.getFilename())
                .contentType(req.getContentType())
                .sizeBytes(sizeBytes)
                .s3Key(destKey)
                .kind(req.getKind())
                .jurisdictions(jurisdictions)
                .firstSeenAt(now)
                .lastUsedAt(now)
                .obligationsExtracted(false)
                .controlsExtracted(false)
                .build();

        try {
            documentRepository.saveIfNotExists(doc);
        } catch (ConditionalCheckFailedException e) {
            // Race: another request saved it first — treat as dedup
            Document reFetched = documentRepository.findById(hash).orElse(doc);
            return DocumentFinalizeResponse.builder()
                    .document(toResponseDTO(reFetched))
                    .deduped(true)
                    .build();
        }

        docJurisdictionRepository.putAll(hash, doc.getJurisdictions(), doc);

        return DocumentFinalizeResponse.builder()
                .document(toResponseDTO(doc))
                .deduped(false)
                .build();
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
                .map(DocumentService::toResponseDTO)
                .orElseThrow(() -> new NotFoundException("Document not found: " + id));
    }

    private static DocumentResponseDTO toResponseDTO(Document doc) {
        return DocumentResponseDTO.builder()
                .id(doc.getId())
                .filename(doc.getFilename())
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
                .build();
    }

    private static DocumentSummaryDTO toSummaryDTO(Document doc) {
        return DocumentSummaryDTO.builder()
                .id(doc.getId())
                .filename(doc.getFilename())
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
