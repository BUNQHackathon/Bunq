package com.bunq.javabackend.service.ai.bedrock;

import java.util.List;

public record MatchableControl(
        String id,
        String description,
        String category,
        List<String> mappedStandards
) {}
