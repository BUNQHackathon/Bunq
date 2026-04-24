package com.bunq.javabackend.model.chat;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;

import java.time.Instant;
import java.util.List;

@DynamoDbBean
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Setter
public class ChatMessage {

    @Getter(onMethod_ = @DynamoDbPartitionKey)
    private String id;

    @Getter(onMethod_ = @DynamoDbAttribute("chatId"))
    private String chatId;

    @Getter(onMethod_ = @DynamoDbAttribute("sessionId"))
    private String sessionId;

    @Getter(onMethod_ = @DynamoDbAttribute("role"))
    private String role;

    @Getter(onMethod_ = @DynamoDbAttribute("content"))
    private String content;

    @Getter(onMethod_ = @DynamoDbAttribute("citations"))
    private List<Citation> citations;

    @Getter(onMethod_ = @DynamoDbAttribute("timestamp"))
    private Instant timestamp;

    @Getter(onMethod_ = @DynamoDbAttribute("tokenUsage"))
    private TokenUsage tokenUsage;
}
