package com.bunq.javabackend.service.bedrock;

public record MatchableObligation(
        String id,
        String subject,
        String action,
        String riskCategory,
        String regulatoryPenalty
) {}
