package com.bunq.javabackend.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvidenceResponseDTO {
    private String id;
    private String relatedMappingId;
    private String evidenceType;
    private String source;
    private Instant collectedAt;
    private String evidenceUrl;
    private String sha256;
    private Instant expiresAt;
    private Double confidenceScore;
    private Boolean humanReviewed;
    private String reviewerId;
    private Instant reviewTimestamp;
    private List<AuditTrailEntryDTO> auditTrail;
    private String sessionId;
    private String s3Key;
    private String description;
    private java.time.Instant uploadedAt;
}
