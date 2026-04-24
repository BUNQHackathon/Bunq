package com.bunq.javabackend.model.evidence;

import com.bunq.javabackend.model.audit.AuditTrailEntry;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;

import java.time.Instant;
import java.util.List;

@DynamoDbBean
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Setter
public class Evidence {

    @Getter(onMethod_ = {@DynamoDbPartitionKey, @DynamoDbAttribute("id")})
    private String id;

    @Getter(onMethod_ = @DynamoDbAttribute("related_mapping_id"))
    private String relatedMappingId;

    @Getter(onMethod_ = @DynamoDbAttribute("evidence_type"))
    private String evidenceType;

    @Getter(onMethod_ = @DynamoDbAttribute("source"))
    private String source;

    @Getter(onMethod_ = @DynamoDbAttribute("collected_at"))
    private Instant collectedAt;

    @Getter(onMethod_ = @DynamoDbAttribute("evidence_url"))
    private String evidenceUrl;

    @Getter(onMethod_ = @DynamoDbAttribute("sha256"))
    private String sha256;

    @Getter(onMethod_ = @DynamoDbAttribute("expires_at"))
    private Instant expiresAt;

    @Getter(onMethod_ = @DynamoDbAttribute("confidence_score"))
    private Double confidenceScore;

    @Getter(onMethod_ = @DynamoDbAttribute("human_reviewed"))
    private Boolean humanReviewed;

    @Getter(onMethod_ = @DynamoDbAttribute("reviewer_id"))
    private String reviewerId;

    @Getter(onMethod_ = @DynamoDbAttribute("review_timestamp"))
    private Instant reviewTimestamp;

    @Getter(onMethod_ = @DynamoDbAttribute("audit_trail"))
    private List<AuditTrailEntry> auditTrail;

    @Getter(onMethod_ = @DynamoDbAttribute("session_id"))
    private String sessionId;

    @Getter(onMethod_ = @DynamoDbAttribute("s3_key"))
    private String s3Key;

    @Getter(onMethod_ = @DynamoDbAttribute("description"))
    private String description;

    @Getter(onMethod_ = @DynamoDbAttribute("uploaded_at"))
    private Instant uploadedAt;
}
