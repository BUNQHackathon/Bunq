package com.bunq.javabackend.service.pipeline;

import java.util.Map;

public record IngestChunk(String sourceS3Key, String text, Map<String, String> metadata) {}
