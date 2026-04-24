package com.bunq.javabackend.model.gap;

import com.bunq.javabackend.model.enums.GapStatus;
import com.bunq.javabackend.model.enums.GapType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbConvertedBy;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;

import java.time.LocalDate;
import java.util.List;

@DynamoDbBean
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Setter
public class Gap {

    @Getter(onMethod_ = {@DynamoDbPartitionKey, @DynamoDbAttribute("id")})
    private String id;

    @Getter(onMethod_ = @DynamoDbAttribute("obligation_id"))
    private String obligationId;

    @Getter(onMethod_ = {@DynamoDbAttribute("gap_type"), @DynamoDbConvertedBy(GapTypeConverter.class)})
    private GapType gapType;

    @Getter(onMethod_ = {@DynamoDbAttribute("gap_status"), @DynamoDbConvertedBy(GapStatusConverter.class)})
    private GapStatus gapStatus;

    @Getter(onMethod_ = @DynamoDbAttribute("severity_dimensions"))
    private SeverityDimensions severityDimensions;

    @Getter(onMethod_ = @DynamoDbAttribute("recommended_actions"))
    private List<RecommendedAction> recommendedActions;

    @Getter(onMethod_ = @DynamoDbAttribute("remediation_deadline"))
    private LocalDate remediationDeadline;

    @Getter(onMethod_ = @DynamoDbAttribute("escalation_required"))
    private Boolean escalationRequired;

    @Getter(onMethod_ = @DynamoDbAttribute("narrative"))
    private String narrative;

    @Getter(onMethod_ = @DynamoDbAttribute("session_id"))
    private String sessionId;

    // 5-dimensional residual risk fields
    @Getter(onMethod_ = @DynamoDbAttribute("severity"))
    private Double severity;

    @Getter(onMethod_ = @DynamoDbAttribute("likelihood"))
    private Double likelihood;

    @Getter(onMethod_ = @DynamoDbAttribute("detectability"))
    private Double detectability;

    @Getter(onMethod_ = @DynamoDbAttribute("blast_radius"))
    private Double blastRadius;

    @Getter(onMethod_ = @DynamoDbAttribute("recoverability"))
    private Double recoverability;

    @Getter(onMethod_ = @DynamoDbAttribute("residual_risk"))
    private Double residualRisk;
}
