package com.bunq.javabackend.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SeverityDimensionsDTO {
    private Double regulatoryUrgency;
    private Double penaltySeverity;
    private Double probability;
    private Double businessImpact;
    private Double combinedRiskScore;
}
