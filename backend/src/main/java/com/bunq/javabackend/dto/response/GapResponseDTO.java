package com.bunq.javabackend.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GapResponseDTO {
    private String id;
    private String obligationId;
    private String gapType;
    private String gapStatus;
    private SeverityDimensionsDTO severityDimensions;
    private List<RecommendedActionDTO> recommendedActions;
    private LocalDate remediationDeadline;
    private Boolean escalationRequired;
    private String narrative;
    private String sessionId;
}
