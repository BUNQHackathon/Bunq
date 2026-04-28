package com.bunq.javabackend.model.launch;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import com.bunq.javabackend.model.enums.RunStatus;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbConvertedBy;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;

import java.util.List;

@DynamoDbBean
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Setter
public class Launch {

    @Getter(onMethod_ = @DynamoDbPartitionKey)
    private String id;

    @Getter(onMethod_ = @DynamoDbAttribute("name"))
    private String name;

    @Getter(onMethod_ = @DynamoDbAttribute("brief"))
    private String brief;

    @Getter(onMethod_ = @DynamoDbAttribute("license"))
    private String license;

    @Getter(onMethod_ = {@DynamoDbAttribute("kind"), @DynamoDbConvertedBy(LaunchKindConverter.class)})
    private LaunchKind kind;

    @Getter(onMethod_ = @DynamoDbAttribute("counterparties"))
    private List<String> counterparties;

    @Getter(onMethod_ = {@DynamoDbAttribute("status"), @DynamoDbConvertedBy(RunStatusConverter.class)})
    private RunStatus status;

    @Getter(onMethod_ = @DynamoDbAttribute("created_at"))
    private String createdAt;

    @Getter(onMethod_ = @DynamoDbAttribute("updated_at"))
    private String updatedAt;
}
