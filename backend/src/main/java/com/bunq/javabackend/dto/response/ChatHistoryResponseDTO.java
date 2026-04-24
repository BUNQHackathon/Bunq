package com.bunq.javabackend.dto.response;

import lombok.Builder;
import lombok.Value;

import java.util.List;

@Value
@Builder
public class ChatHistoryResponseDTO {

    String chatId;
    List<ChatMessageResponseDTO> messages;
}
