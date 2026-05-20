package com.bunq.javabackend.helper;

import com.bunq.javabackend.dto.response.PresignedPutResponseDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.nio.file.Paths;
import java.time.Duration;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class S3PresignHelper {

    private final S3Presigner s3Presigner;

    @Value("${aws.s3.uploads-bucket}")
    private String uploadsBucket;

    public PresignedPutResponseDTO generatePresignedPut(String sessionId, String fileName, String contentType, String sha256Base64) {
        // Path-traversal prevention: strip directory segments, then allow only safe characters.
        fileName = Paths.get(fileName).getFileName().toString();
        fileName = fileName.replaceAll("[^A-Za-z0-9._-]", "_");
        if (fileName.isEmpty() || fileName.startsWith(".")) {
            fileName = "file_" + fileName;
        }
        String s3Key = "sessions/" + sessionId + "/" + UUID.randomUUID() + "/" + fileName;
        PutObjectRequest putRequest = PutObjectRequest.builder()
                .bucket(uploadsBucket)
                .key(s3Key)
                .contentType(contentType != null ? contentType : "application/octet-stream")
                .checksumSHA256(sha256Base64)
                .build();
        PresignedPutObjectRequest presigned = s3Presigner.presignPutObject(r -> r
                .signatureDuration(Duration.ofMinutes(15))
                .putObjectRequest(putRequest));
        return PresignedPutResponseDTO.builder()
                .presignedPutUrl(presigned.url().toString())
                .s3Key(s3Key)
                .build();
    }

    public EvidencePresignResult presignEvidencePut(String sessionId, String filename, String contentType, String sha256Base64) {
        // Path-traversal prevention: keep only the extension and restrict to safe alphanumeric chars.
        String raw = filename.contains(".") ? filename.substring(filename.lastIndexOf('.') + 1) : "bin";
        String ext = raw.replaceAll("[^A-Za-z0-9]", "");
        if (ext.isEmpty() || ext.length() > 16) {
            ext = "bin";
        }
        String s3Key = "evidence/" + sessionId + "/" + UUID.randomUUID() + "." + ext;
        // checksumSHA256 bakes x-amz-checksum-sha256 into SignedHeaders; checksumAlgorithm alone does not
        PutObjectRequest putRequest = PutObjectRequest.builder()
                .bucket(uploadsBucket)
                .key(s3Key)
                .contentType(contentType != null ? contentType : "application/octet-stream")
                .checksumSHA256(sha256Base64)
                .build();
        PresignedPutObjectRequest presigned = s3Presigner.presignPutObject(r -> r
                .signatureDuration(Duration.ofMinutes(15))
                .putObjectRequest(putRequest));
        return new EvidencePresignResult(s3Key, presigned.url().toString(), 900);
    }

    public record EvidencePresignResult(String s3Key, String uploadUrl, int expiresInSeconds) {}

    public DocumentPresignResult presignDocumentUpload(String filename, String contentType, String sha256Base64) {
        // Path-traversal prevention: keep only the extension and restrict to safe alphanumeric chars.
        String raw = filename.contains(".") ? filename.substring(filename.lastIndexOf('.') + 1) : "bin";
        String ext = raw.replaceAll("[^A-Za-z0-9]", "");
        if (ext.isEmpty() || ext.length() > 16) {
            ext = "bin";
        }
        String incomingKey = "documents/incoming/" + UUID.randomUUID() + "." + ext;
        // Sign the concrete checksum header the browser sends during the presigned PUT.
        PutObjectRequest putRequest = PutObjectRequest.builder()
                .bucket(uploadsBucket)
                .key(incomingKey)
                .contentType(contentType != null ? contentType : "application/octet-stream")
                .checksumSHA256(sha256Base64)
                .build();
        PresignedPutObjectRequest presigned = s3Presigner.presignPutObject(r -> r
                .signatureDuration(Duration.ofMinutes(15))
                .putObjectRequest(putRequest));
        return new DocumentPresignResult(incomingKey, presigned.url().toString(), 900);
    }

    public record DocumentPresignResult(String incomingKey, String uploadUrl, int expiresInSeconds) {}

    public String generatePresignedGet(String s3Key) {
        GetObjectRequest getRequest = GetObjectRequest.builder()
                .bucket(uploadsBucket)
                .key(s3Key)
                .build();
        PresignedGetObjectRequest presigned = s3Presigner.presignGetObject(r -> r
                .signatureDuration(Duration.ofMinutes(15))
                .getObjectRequest(getRequest));
        return presigned.url().toString();
    }
}
