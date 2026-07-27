package com.bunq.javabackend.service.ai.bedrock;

public record MatchResult(
        String controlId,
        double confidence,
        String reason,
        String mappingType,             // raw string ("full"|"partial"|...) — caller maps to enum
        String meetsJurisdictionSpecifics, // "yes"|"no"|"not_applicable"|null — null when the model omitted it
        String missingSpecific          // one-sentence delta description; null/empty when not applicable
) {
    /** Backward-compatible constructor for callers that predate the jurisdiction-delta fields. */
    public MatchResult(String controlId, double confidence, String reason, String mappingType) {
        this(controlId, confidence, reason, mappingType, null, null);
    }
}
