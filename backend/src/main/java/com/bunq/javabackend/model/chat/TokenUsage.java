package com.bunq.javabackend.model.chat;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;

@DynamoDbBean
@NoArgsConstructor
@Setter
public class TokenUsage {

    @Getter(onMethod_ = @DynamoDbAttribute("inputTokens"))
    private Integer inputTokens;

    @Getter(onMethod_ = @DynamoDbAttribute("outputTokens"))
    private Integer outputTokens;

    @Getter(onMethod_ = @DynamoDbAttribute("cacheReadTokens"))
    private Integer cacheReadTokens;

    @Getter(onMethod_ = @DynamoDbAttribute("cacheCreationTokens"))
    private Integer cacheCreationTokens;
}
