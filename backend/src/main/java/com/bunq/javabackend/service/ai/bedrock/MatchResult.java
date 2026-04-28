package com.bunq.javabackend.service.ai.bedrock;

public record MatchResult(
        String controlId,
        double confidence,
        String reason,
        String mappingType   // raw string ("full"|"partial"|...) — caller maps to enum
) {}
