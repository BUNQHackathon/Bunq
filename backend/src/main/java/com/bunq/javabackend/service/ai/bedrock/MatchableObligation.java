package com.bunq.javabackend.service.ai.bedrock;

public record MatchableObligation(
        String id,
        String subject,
        String action,
        String riskCategory,
        String regulatoryPenalty
) {}
