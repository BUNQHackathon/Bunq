package com.bunq.javabackend.service;

import com.bunq.javabackend.dto.response.PresignedUrlResponseDTO;
import com.bunq.javabackend.exception.ForbiddenException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class FilesService {

    private static final Set<String> ALLOWED_BUCKETS = Set.of(
        "launchlens-kb-regulations",
        "launchlens-kb-policies",
        "launchlens-kb-controls"
    );

    private final S3Presigner presigner;

    public PresignedUrlResponseDTO presignKbObject(String s3Uri) {
        if (!s3Uri.startsWith("s3://")) {
            throw new IllegalArgumentException("Invalid s3Uri: " + s3Uri);
        }
        String stripped = s3Uri.substring(5);
        int hashIdx = stripped.indexOf('#');
        if (hashIdx != -1) stripped = stripped.substring(0, hashIdx);
        int slashIdx = stripped.indexOf('/');
        if (slashIdx == -1) {
            throw new IllegalArgumentException("Invalid s3Uri: " + s3Uri);
        }
        String bucket = stripped.substring(0, slashIdx);
        String key = stripped.substring(slashIdx + 1);

        if (!ALLOWED_BUCKETS.contains(bucket)) {
            throw new ForbiddenException("Bucket not allowed: " + bucket);
        }

        GetObjectRequest getReq = GetObjectRequest.builder().bucket(bucket).key(key).build();
        GetObjectPresignRequest presignReq = GetObjectPresignRequest.builder()
            .signatureDuration(Duration.ofMinutes(15))
            .getObjectRequest(getReq)
            .build();
        PresignedGetObjectRequest presigned = presigner.presignGetObject(presignReq);
        String expires = Instant.now().plus(Duration.ofMinutes(15)).toString();
        return new PresignedUrlResponseDTO(presigned.url().toString(), expires);
    }
}
