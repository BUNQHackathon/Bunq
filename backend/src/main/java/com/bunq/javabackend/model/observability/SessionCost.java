package com.bunq.javabackend.model.observability;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;

import java.util.Map;

/**
 * DynamoDB item: one row per sessionId, updated on every Bedrock call.
 *
 * <p>Table name: configured via {@code aws.dynamodb.session-cost-table}.
 * PK: sessionId (String).
 * total_usd_cents stores cost * 100 as a long to avoid floating-point drift.
 *
 * <p>Table must be provisioned externally (see infra/dynamodb.tf — add
 * {@code "session-costs"} to {@code local.dynamodb_tables}).
 */
@DynamoDbBean
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Setter
public class SessionCost {

    @Getter(onMethod_ = {@DynamoDbPartitionKey, @DynamoDbAttribute("sessionId")})
    private String sessionId;

    @Getter(onMethod_ = @DynamoDbAttribute("total_input_tokens"))
    private long totalInputTokens;

    @Getter(onMethod_ = @DynamoDbAttribute("total_output_tokens"))
    private long totalOutputTokens;

    @Getter(onMethod_ = @DynamoDbAttribute("total_cache_creation_tokens"))
    private long totalCacheCreationTokens;

    @Getter(onMethod_ = @DynamoDbAttribute("total_cache_read_tokens"))
    private long totalCacheReadTokens;

    /** Stored as integer cents (USD * 100) to avoid floating-point drift. */
    @Getter(onMethod_ = @DynamoDbAttribute("total_usd_cents"))
    private long totalUsdCents;

    /** Per-stage counters serialised as a DynamoDB Map. Key = stage name. */
    @Getter(onMethod_ = @DynamoDbAttribute("per_stage"))
    private Map<String, StageCost> perStage;

    /** ISO-8601 timestamp of last update. */
    @Getter(onMethod_ = @DynamoDbAttribute("updated_at"))
    private String updatedAt;
}
