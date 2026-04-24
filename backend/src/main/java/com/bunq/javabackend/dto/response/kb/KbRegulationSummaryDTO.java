package com.bunq.javabackend.dto.response.kb;

public record KbRegulationSummaryDTO(
    String id,
    String key,
    String title,
    String category,
    String jurisdiction,
    String type,
    long size,
    String updated
) {}
