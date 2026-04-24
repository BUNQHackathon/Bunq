package com.bunq.javabackend.dto.response;

import java.util.Map;

public record ChatGraphNodeDTO(
        String id,
        String type,       // "obligation"|"control"|"gap"
        String label,
        Map<String, Object> metadata
) {}
