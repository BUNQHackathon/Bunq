package com.bunq.javabackend.service;

import com.bunq.javabackend.client.SidecarClient;
import com.bunq.javabackend.dto.request.EvidenceFinalizeRequest;
import com.bunq.javabackend.dto.request.EvidencePresignRequest;
import com.bunq.javabackend.dto.response.EvidencePresignResponse;
import com.bunq.javabackend.dto.response.EvidenceResponseDTO;
import com.bunq.javabackend.dto.response.sidecar.GraphDAG;
import com.bunq.javabackend.exception.NotFoundException;
import com.bunq.javabackend.exception.SidecarCommunicationException;
import com.bunq.javabackend.helper.S3PresignHelper;
import com.bunq.javabackend.helper.mapper.EvidenceMapper;
import com.bunq.javabackend.model.evidence.Evidence;
import com.bunq.javabackend.repository.EvidenceRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ChecksumMode;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class EvidenceService {

    private static final Logger log = LoggerFactory.getLogger(EvidenceService.class);

    private final EvidenceRepository evidenceRepository;
    private final SidecarClient sidecarClient;
    private final S3Client s3Client;
    private final S3PresignHelper s3PresignHelper;

    @Value("${aws.s3.uploads-bucket}")
    private String uploadsBucket;

    public EvidenceResponseDTO get(String id) {
        return evidenceRepository.findById(id)
                .map(EvidenceMapper::toDto)
                .orElseThrow(() -> new NotFoundException("Evidence not found: " + id));
    }

    public String hashFromS3(String s3Key) {
        HeadObjectResponse resp = s3Client.headObject(HeadObjectRequest.builder()
                .bucket(uploadsBucket).key(s3Key)
                .checksumMode(ChecksumMode.ENABLED)
                .build());
        String sha256b64 = resp.checksumSHA256();
        if (sha256b64 == null) throw new IllegalStateException(
                "No SHA-256 stored on object " + s3Key + " (was it uploaded before checksum support?)");
        return sha256b64;
    }

    public EvidencePresignResponse presign(String sessionId, EvidencePresignRequest req) {
        S3PresignHelper.EvidencePresignResult result =
                s3PresignHelper.presignEvidencePut(sessionId, req.getFilename(), req.getContentType(), req.getSha256());
        return EvidencePresignResponse.builder()
                .s3Key(result.s3Key())
                .uploadUrl(result.uploadUrl())
                .expiresInSeconds(result.expiresInSeconds())
                .build();
    }

    public EvidenceResponseDTO finalize(String sessionId, EvidenceFinalizeRequest req) {
        String sha256 = hashFromS3(req.getS3Key());
        Evidence evidence = Evidence.builder()
                .id(UUID.randomUUID().toString())
                .sessionId(sessionId)
                .relatedMappingId(req.getMappingId())
                .s3Key(req.getS3Key())
                .sha256(sha256)
                .description(req.getDescription())
                .uploadedAt(Instant.now())
                .build();
        evidenceRepository.save(evidence);
        return EvidenceMapper.toDto(evidence);
    }

    public GraphDAG getProofTree(String mappingId) {
        try {
            return sidecarClient.getProofTree(mappingId);
        } catch (SidecarCommunicationException e) {
            log.warn("Sidecar unavailable for getProofTree mappingId={}: {}", mappingId, e.getMessage());
            return GraphDAG.builder().nodes(List.of()).edges(List.of()).build();
        }
    }

    public GraphDAG getComplianceMap(String sessionId) {
        try {
            return sidecarClient.getComplianceMap(sessionId);
        } catch (SidecarCommunicationException e) {
            log.warn("Sidecar unavailable for getComplianceMap sessionId={}: {}", sessionId, e.getMessage());
            return GraphDAG.builder().nodes(List.of()).edges(List.of()).build();
        }
    }
}
