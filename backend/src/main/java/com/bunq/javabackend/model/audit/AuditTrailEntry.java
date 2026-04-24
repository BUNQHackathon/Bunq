package com.bunq.javabackend.model.audit;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;

import java.time.Instant;

@DynamoDbBean
@NoArgsConstructor
@Setter
public class AuditTrailEntry {

    @Getter(onMethod_ = @DynamoDbAttribute("action"))
    private String action;

    @Getter(onMethod_ = @DynamoDbAttribute("timestamp"))
    private Instant timestamp;

    @Getter(onMethod_ = @DynamoDbAttribute("actor"))
    private String actor;

    @Getter(onMethod_ = @DynamoDbAttribute("decision"))
    private String decision;

    @Getter(onMethod_ = @DynamoDbAttribute("prev_hash"))
    private String prevHash;
}
