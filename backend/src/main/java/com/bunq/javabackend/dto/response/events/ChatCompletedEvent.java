package com.bunq.javabackend.dto.response.events;

import com.bunq.javabackend.dto.response.TokenUsageDTO;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;

@Value
@Builder
public class ChatCompletedEvent extends PipelineEvent {

    String sessionId;
    Instant timestamp;
    String chatId;
    String messageId;
    TokenUsageDTO tokenUsage;

    @Override
    public String getType() {
        return "chat_completed";
    }
}
