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
public class MappingResponseDTO {
    private String id;
    private String obligationId;
    private String controlId;
    private Double mappingConfidence;
    private String mappingType;
    private String gapStatus;
    private String semanticReason;
    private List<String> structuralMatchTags;
    private List<String> evidenceLinks;
    private String reviewerNotes;
    private Instant lastReviewed;
    private String sessionId;
}
