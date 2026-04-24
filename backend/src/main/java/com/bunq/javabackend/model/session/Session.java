package com.bunq.javabackend.model.session;

import com.bunq.javabackend.model.enums.SessionState;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbConvertedBy;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondarySortKey;

import java.util.List;
import java.util.Map;

@DynamoDbBean
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Setter
public class Session {

    @Getter(onMethod_ = @DynamoDbPartitionKey)
    private String id;

    @Getter(onMethod_ = {@DynamoDbAttribute("state"), @DynamoDbConvertedBy(SessionStateConverter.class)})
    private SessionState state;

    @Getter(onMethod_ = @DynamoDbAttribute("regulation"))
    private String regulation;

    @Getter(onMethod_ = @DynamoDbAttribute("policy"))
    private String policy;

    @Getter(onMethod_ = @DynamoDbAttribute("counterparties"))
    private List<String> counterparties;

    @Getter(onMethod_ = @DynamoDbAttribute("document_ids"))
    private List<String> documentIds;

    @Getter(onMethod_ = @DynamoDbAttribute("verdict"))
    private String verdict;

    @Getter(onMethod_ = @DynamoDbAttribute("errorMessage"))
    private String errorMessage;

    @Getter(onMethod_ = {
        @DynamoDbAttribute("createdAt"),
        @DynamoDbSecondarySortKey(indexNames = "launch-sessions-index")
    })
    private String createdAt;

    @Getter(onMethod_ = @DynamoDbAttribute("updatedAt"))
    private String updatedAt;

    @Getter(onMethod_ = {
        @DynamoDbAttribute("launch_id"),
        @DynamoDbSecondaryPartitionKey(indexNames = "launch-sessions-index")
    })
    private String launchId;

    @Getter(onMethod_ = @DynamoDbAttribute("jurisdiction_code"))
    private String jurisdictionCode;
}
