package com.bunq.javabackend.model.document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

import java.time.Instant;

@DynamoDbBean
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Setter
public class DocJurisdictionItem {

    @Getter(onMethod_ = {@DynamoDbPartitionKey, @DynamoDbAttribute("jurisdiction")})
    private String jurisdiction;

    @Getter(onMethod_ = {@DynamoDbSortKey, @DynamoDbAttribute("document_id")})
    private String documentId;

    @Getter(onMethod_ = @DynamoDbAttribute("kind"))
    private String kind;

    @Getter(onMethod_ = @DynamoDbAttribute("filename"))
    private String filename;

    @Getter(onMethod_ = @DynamoDbAttribute("last_used_at"))
    private Instant lastUsedAt;
}
