package com.bunq.javabackend.model.obligation;

import com.bunq.javabackend.model.enums.DeonticOperator;
import com.bunq.javabackend.model.enums.ObligationType;
import com.bunq.javabackend.model.enums.Severity;
import com.bunq.javabackend.model.gap.SeverityConverter;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbConvertedBy;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;

import java.time.Instant;
import java.util.List;

@DynamoDbBean
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Setter
public class Obligation {

    @Getter(onMethod_ = {@DynamoDbPartitionKey, @DynamoDbAttribute("id")})
    private String id;

    @Getter(onMethod_ = @DynamoDbAttribute("source"))
    private ObligationSource source;

    @Getter(onMethod_ = {@DynamoDbAttribute("obligation_type"), @DynamoDbConvertedBy(ObligationTypeConverter.class)})
    private ObligationType obligationType;

    @Getter(onMethod_ = {@DynamoDbAttribute("deontic"), @DynamoDbConvertedBy(DeonticOperatorConverter.class)})
    private DeonticOperator deontic;

    @Getter(onMethod_ = @DynamoDbAttribute("subject"))
    private String subject;

    @Getter(onMethod_ = @DynamoDbAttribute("action"))
    private String action;

    @Getter(onMethod_ = @DynamoDbAttribute("conditions"))
    private List<String> conditions;

    @Getter(onMethod_ = @DynamoDbAttribute("risk_category"))
    private String riskCategory;

    @Getter(onMethod_ = @DynamoDbAttribute("applicable_jurisdictions"))
    private List<String> applicableJurisdictions;

    @Getter(onMethod_ = @DynamoDbAttribute("applicable_entities"))
    private List<String> applicableEntities;

    @Getter(onMethod_ = {@DynamoDbAttribute("severity"), @DynamoDbConvertedBy(SeverityConverter.class)})
    private Severity severity;

    @Getter(onMethod_ = @DynamoDbAttribute("regulatory_penalty_range"))
    private String regulatoryPenaltyRange;

    @Getter(onMethod_ = @DynamoDbAttribute("extracted_at"))
    private Instant extractedAt;

    @Getter(onMethod_ = @DynamoDbAttribute("extraction_confidence"))
    private Double extractionConfidence;

    @Getter(onMethod_ = @DynamoDbAttribute("session_id"))
    private String sessionId;

    @Getter(onMethod_ = @DynamoDbAttribute("regulation_id"))
    private String regulationId;

    @Getter(onMethod_ = {
        @DynamoDbAttribute("document_id"),
        @DynamoDbSecondaryPartitionKey(indexNames = "document-id-index")
    })
    private String documentId;
}
