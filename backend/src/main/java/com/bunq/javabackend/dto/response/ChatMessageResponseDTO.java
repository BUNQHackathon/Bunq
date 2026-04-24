package com.bunq.javabackend.dto.response;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.List;

@Value
@Builder
public class ChatMessageResponseDTO {

    String id;
    String chatId;
    String role;
    String content;
    List<CitationDTO> citations;
    Instant timestamp;
    TokenUsageDTO tokenUsage;
}
