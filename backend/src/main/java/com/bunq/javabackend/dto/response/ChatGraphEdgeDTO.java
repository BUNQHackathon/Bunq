package com.bunq.javabackend.dto.response;

public record ChatGraphEdgeDTO(
        String source,
        String target,
        String type,       // "maps_to"|"has_gap"
        Double confidence  // nullable for has_gap
) {}
