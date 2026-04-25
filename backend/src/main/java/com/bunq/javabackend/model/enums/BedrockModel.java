package com.bunq.javabackend.model.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public enum BedrockModel {
    OPUS("eu.anthropic.claude-opus-4-7", 8192),
    SONNET("eu.anthropic.claude-sonnet-4-6", 4096),
    HAIKU("eu.anthropic.claude-haiku-4-5-20251001-v1:0", 2048),
    NOVA_PRO("eu.amazon.nova-pro-v1:0", 5000),
    NOVA_LITE("eu.amazon.nova-lite-v1:0", 5000);

    @Getter
    private final String modelId;

    @Getter
    private final int maxTokens;
}
