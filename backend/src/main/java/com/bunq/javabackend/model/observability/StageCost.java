package com.bunq.javabackend.model.observability;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;

/** Per-stage token counters nested inside SessionCost.perStage. */
@DynamoDbBean
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Setter
public class StageCost {

    @Getter(onMethod_ = @DynamoDbAttribute("input_tokens"))
    private long inputTokens;

    @Getter(onMethod_ = @DynamoDbAttribute("output_tokens"))
    private long outputTokens;

    @Getter(onMethod_ = @DynamoDbAttribute("cache_creation_tokens"))
    private long cacheCreationTokens;

    @Getter(onMethod_ = @DynamoDbAttribute("cache_read_tokens"))
    private long cacheReadTokens;

    @Getter(onMethod_ = @DynamoDbAttribute("usd_cents"))
    private long usdCents;
}
