package com.bunq.javabackend.dto.response.events;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;

@Value
@Builder
public class ChatFailedEvent extends PipelineEvent {

    String sessionId;
    Instant timestamp;
    String chatId;
    String errorCode;
    String message;

    @Override
    public String getType() {
        return "chat_failed";
    }
}
