package com.bunq.javabackend.dto.response.events;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;

// Chat events reuse PipelineEvent base for SSE dispatch compatibility.
// chatId is stored in the sessionId field inherited from PipelineEvent.
@Value
@Builder
public class ChatStartedEvent extends PipelineEvent {

    String sessionId;
    Instant timestamp;
    String chatId;

    @Override
    public String getType() {
        return "chat_started";
    }
}
