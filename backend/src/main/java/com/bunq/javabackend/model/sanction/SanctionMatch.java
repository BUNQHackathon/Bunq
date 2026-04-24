package com.bunq.javabackend.model.sanction;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;

import java.time.Instant;
import java.util.List;

@DynamoDbBean
@NoArgsConstructor
@Setter
public class SanctionMatch {

    @Getter(onMethod_ = @DynamoDbAttribute("list_source"))
    private String listSource;

    @Getter(onMethod_ = @DynamoDbAttribute("entity_name"))
    private String entityName;

    @Getter(onMethod_ = @DynamoDbAttribute("aliases"))
    private List<String> aliases;

    @Getter(onMethod_ = @DynamoDbAttribute("match_score"))
    private Double matchScore;

    @Getter(onMethod_ = @DynamoDbAttribute("list_version_timestamp"))
    private Instant listVersionTimestamp;
}
