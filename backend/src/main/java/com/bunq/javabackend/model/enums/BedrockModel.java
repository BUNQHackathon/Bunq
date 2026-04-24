package com.bunq.javabackend.model.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public enum BedrockModel {
    OPUS("eu.anthropic.claude-opus-4-7", 8192),
    SONNET("eu.anthropic.claude-sonnet-4-6", 4096),
    HAIKU("eu.anthropic.claude-haiku-4-5-20251001-v1:0", 2048);

    @Getter
    private final String modelId;

    @Getter
    private final int maxTokens;
}
