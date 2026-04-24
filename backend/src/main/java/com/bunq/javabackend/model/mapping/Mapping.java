package com.bunq.javabackend.model.mapping;

import com.bunq.javabackend.model.enums.GapStatus;
import com.bunq.javabackend.model.enums.MappingType;
import com.bunq.javabackend.model.gap.GapStatusConverter;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbConvertedBy;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@DynamoDbBean
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Setter
public class Mapping {

    @Getter(onMethod_ = {@DynamoDbPartitionKey, @DynamoDbAttribute("id")})
    private String id;

    @Getter(onMethod_ = @DynamoDbAttribute("obligation_id"))
    private String obligationId;

    @Getter(onMethod_ = @DynamoDbAttribute("control_id"))
    private String controlId;

    @Getter(onMethod_ = @DynamoDbAttribute("mapping_confidence"))
    private Double mappingConfidence;

    @Getter(onMethod_ = {@DynamoDbAttribute("mapping_type"), @DynamoDbConvertedBy(MappingTypeConverter.class)})
    private MappingType mappingType;

    @Getter(onMethod_ = {@DynamoDbAttribute("gap_status"), @DynamoDbConvertedBy(GapStatusConverter.class)})
    private GapStatus gapStatus;

    @Getter(onMethod_ = @DynamoDbAttribute("semantic_reason"))
    private String semanticReason;

    @Getter(onMethod_ = @DynamoDbAttribute("structural_match_tags"))
    private List<String> structuralMatchTags;

    @Getter(onMethod_ = @DynamoDbAttribute("evidence_links"))
    private List<String> evidenceLinks;

    @Getter(onMethod_ = @DynamoDbAttribute("reviewer_notes"))
    private String reviewerNotes;

    @Getter(onMethod_ = @DynamoDbAttribute("last_reviewed"))
    private Instant lastReviewed;

    @Getter(onMethod_ = @DynamoDbAttribute("session_id"))
    private String sessionId;

    @Getter(onMethod_ = @DynamoDbAttribute("metadata"))
    private Map<String, String> metadata;
}
