package com.bunq.javabackend.model.gap;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;

@DynamoDbBean
@NoArgsConstructor
@Setter
public class SeverityDimensions {

    @Getter(onMethod_ = @DynamoDbAttribute("regulatory_urgency"))
    private Double regulatoryUrgency;

    @Getter(onMethod_ = @DynamoDbAttribute("penalty_severity"))
    private Double penaltySeverity;

    @Getter(onMethod_ = @DynamoDbAttribute("probability"))
    private Double probability;

    @Getter(onMethod_ = @DynamoDbAttribute("business_impact"))
    private Double businessImpact;

    @Getter(onMethod_ = @DynamoDbAttribute("combined_risk_score"))
    private Double combinedRiskScore;
}
