package com.bunq.javabackend.dto.response.events;

import com.bunq.javabackend.dto.response.CitationDTO;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.List;

@Value
@Builder
public class ChatCitationsEvent extends PipelineEvent {

    String sessionId;
    Instant timestamp;
    String chatId;
    List<CitationDTO> citations;

    @Override
    public String getType() {
        return "chat_citations";
    }
}
