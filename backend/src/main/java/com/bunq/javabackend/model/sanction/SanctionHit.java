package com.bunq.javabackend.model.sanction;

import com.bunq.javabackend.model.enums.SanctionMatchStatus;
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
public class SanctionHit {

    @Getter(onMethod_ = {@DynamoDbPartitionKey, @DynamoDbAttribute("id")})
    private String id;

    @Getter(onMethod_ = @DynamoDbAttribute("session_id"))
    private String sessionId;

    @Getter(onMethod_ = @DynamoDbAttribute("counterparty"))
    private Counterparty counterparty;

    @Getter(onMethod_ = {@DynamoDbAttribute("match_status"), @DynamoDbConvertedBy(SanctionMatchStatusConverter.class)})
    private SanctionMatchStatus matchStatus;

    @Getter(onMethod_ = @DynamoDbAttribute("hits"))
    private List<SanctionMatch> hits;

    @Getter(onMethod_ = @DynamoDbAttribute("entity_metadata"))
    private Map<String, String> entityMetadata;

    @Getter(onMethod_ = @DynamoDbAttribute("screened_at"))
    private Instant screenedAt;
}
