package com.bunq.javabackend.dto.response;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;

@Value
@Builder
public class ChatSummaryResponseDTO {

    String chatId;
    String sessionId;
    String title;
    Instant createdAt;
    Instant updatedAt;
    int messageCount;
}
