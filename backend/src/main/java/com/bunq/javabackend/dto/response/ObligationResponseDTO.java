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
public class ObligationResponseDTO {
    private String id;
    private ObligationSourceDTO source;
    private String obligationType;
    private String deontic;
    private String subject;
    private String action;
    private List<String> conditions;
    private String riskCategory;
    private List<String> applicableJurisdictions;
    private List<String> applicableEntities;
    private String severity;
    private String regulatoryPenaltyRange;
    private Instant extractedAt;
    private Double extractionConfidence;
    private String sessionId;
    private String regulationId;
}
