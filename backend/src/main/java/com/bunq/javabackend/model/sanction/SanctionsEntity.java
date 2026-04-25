package com.bunq.javabackend.model.sanction;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;

import java.util.List;

@DynamoDbBean
@NoArgsConstructor
@Setter
public class SanctionsEntity {

    @Getter(onMethod_ = {@DynamoDbPartitionKey, @DynamoDbAttribute("id")})
    private String id;

    @Getter(onMethod_ = @DynamoDbAttribute("list_source"))
    private String listSource;

    @Getter(onMethod_ = @DynamoDbAttribute("entity_name"))
    private String entityName;

    @Getter(onMethod_ = @DynamoDbAttribute("entity_name_normalized"))
    private String entityNameNormalized;

    @Getter(onMethod_ = @DynamoDbAttribute("aliases"))
    private List<String> aliases;

    @Getter(onMethod_ = @DynamoDbAttribute("country"))
    private String country;

    @Getter(onMethod_ = @DynamoDbAttribute("type"))
    private String type;

    @Getter(onMethod_ = @DynamoDbAttribute("list_entry_id"))
    private String listEntryId;

    @Getter(onMethod_ = @DynamoDbAttribute("list_version_timestamp"))
    private String listVersionTimestamp;
}
