package com.bunq.javabackend.helper.mapper;

import com.bunq.javabackend.dto.response.AuditTrailEntryDTO;
import com.bunq.javabackend.dto.response.EvidenceResponseDTO;
import com.bunq.javabackend.model.audit.AuditTrailEntry;
import com.bunq.javabackend.model.evidence.Evidence;

import java.util.List;

public class EvidenceMapper {

    public static EvidenceResponseDTO toDto(Evidence source) {
        return EvidenceResponseDTO.builder()
                .id(source.getId())
                .relatedMappingId(source.getRelatedMappingId())
                .evidenceType(source.getEvidenceType())
                .source(source.getSource())
                .collectedAt(source.getCollectedAt())
                .evidenceUrl(source.getEvidenceUrl())
                .sha256(source.getSha256())
                .expiresAt(source.getExpiresAt())
                .confidenceScore(source.getConfidenceScore())
                .humanReviewed(source.getHumanReviewed())
                .reviewerId(source.getReviewerId())
                .reviewTimestamp(source.getReviewTimestamp())
                .auditTrail(toAuditTrailDtos(source.getAuditTrail()))
                .sessionId(source.getSessionId())
                .s3Key(source.getS3Key())
                .description(source.getDescription())
                .uploadedAt(source.getUploadedAt())
                .build();
    }

    private static List<AuditTrailEntryDTO> toAuditTrailDtos(List<AuditTrailEntry> entries) {
        if (entries == null) return null;
        return entries.stream().map(e -> AuditTrailEntryDTO.builder()
                .action(e.getAction())
                .timestamp(e.getTimestamp())
                .actor(e.getActor())
                .decision(e.getDecision())
                .prevHash(e.getPrevHash())
                .build()).toList();
    }
}
