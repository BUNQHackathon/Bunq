package com.bunq.javabackend.model.audit;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondarySortKey;

import java.time.Instant;

@DynamoDbBean
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Setter
public class AuditLogEntry {

    @Getter(onMethod_ = {@DynamoDbPartitionKey, @DynamoDbAttribute("id")})
    private String id;

    @Getter(onMethod_ = {
        @DynamoDbAttribute("session_id"),
        @DynamoDbSecondaryPartitionKey(indexNames = "session_id-timestamp-index")
    })
    private String sessionId;

    @Getter(onMethod_ = @DynamoDbAttribute("mapping_id"))
    private String mappingId;

    @Getter(onMethod_ = @DynamoDbAttribute("action"))
    private String action;

    @Getter(onMethod_ = @DynamoDbAttribute("actor"))
    private String actor;

    @Getter(onMethod_ = {
        @DynamoDbAttribute("timestamp"),
        @DynamoDbSecondarySortKey(indexNames = "session_id-timestamp-index")
    })
    private Instant timestamp;

    @Getter(onMethod_ = @DynamoDbAttribute("prev_hash"))
    private String prevHash;

    @Getter(onMethod_ = @DynamoDbAttribute("entry_hash"))
    private String entryHash;

    @Getter(onMethod_ = @DynamoDbAttribute("payload_json"))
    private String payloadJson;
}
