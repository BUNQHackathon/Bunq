package com.bunq.javabackend.model.audit;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;

import java.time.Instant;

@DynamoDbBean
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Setter
public class AuditChainTail {

    @Getter(onMethod_ = {@DynamoDbPartitionKey, @DynamoDbAttribute("session_id")})
    private String sessionId;

    @Getter(onMethod_ = @DynamoDbAttribute("tail_hash"))
    private String tailHash;

    @Getter(onMethod_ = @DynamoDbAttribute("tail_entry_id"))
    private String tailEntryId;

    @Getter(onMethod_ = @DynamoDbAttribute("updated_at"))
    private Instant updatedAt;
}
