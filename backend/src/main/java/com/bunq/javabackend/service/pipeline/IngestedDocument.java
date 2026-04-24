package com.bunq.javabackend.service.pipeline;

/**
 * Carries per-document text after IngestStage resolves it, so downstream stages
 * (Phase 4) can iterate per-document without re-reading ctx.regulation/policy.
 */
public record IngestedDocument(String documentId, String kind, String text) {}
