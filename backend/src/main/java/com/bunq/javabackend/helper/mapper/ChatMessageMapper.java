package com.bunq.javabackend.helper.mapper;

import com.bunq.javabackend.dto.response.CitationDTO;
import com.bunq.javabackend.dto.response.ChatMessageResponseDTO;
import com.bunq.javabackend.dto.response.TokenUsageDTO;
import com.bunq.javabackend.model.chat.ChatMessage;
import com.bunq.javabackend.model.chat.Citation;
import com.bunq.javabackend.model.chat.TokenUsage;

import java.util.List;

public class ChatMessageMapper {

    public static ChatMessageResponseDTO toDto(ChatMessage msg) {
        return ChatMessageResponseDTO.builder()
                .id(msg.getId())
                .chatId(msg.getChatId())
                .role(msg.getRole())
                .content(msg.getContent())
                .citations(toCitationDtos(msg.getCitations()))
                .timestamp(msg.getTimestamp())
                .tokenUsage(toTokenUsageDto(msg.getTokenUsage()))
                .build();
    }

    public static CitationDTO toCitationDto(Citation c) {
        if (c == null) return null;
        return CitationDTO.builder()
                .kbType(c.getKbType())
                .chunkId(c.getChunkId())
                .score(c.getScore())
                .s3Uri(c.getS3Uri())
                .sourceText(c.getSourceText())
                .build();
    }

    public static TokenUsageDTO toTokenUsageDto(TokenUsage u) {
        if (u == null) return null;
        return TokenUsageDTO.builder()
                .inputTokens(u.getInputTokens())
                .outputTokens(u.getOutputTokens())
                .cacheReadTokens(u.getCacheReadTokens())
                .cacheCreationTokens(u.getCacheCreationTokens())
                .build();
    }

    private static List<CitationDTO> toCitationDtos(List<Citation> citations) {
        if (citations == null) return List.of();
        return citations.stream().map(ChatMessageMapper::toCitationDto).toList();
    }
}
