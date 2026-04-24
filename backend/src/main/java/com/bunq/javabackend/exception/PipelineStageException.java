package com.bunq.javabackend.exception;

import com.bunq.javabackend.service.pipeline.PipelineStage;

public class PipelineStageException extends RuntimeException {

    private final PipelineStage stage;

    public PipelineStageException(PipelineStage stage, String message, Throwable cause) {
        super(message, cause);
        this.stage = stage;
    }

    public PipelineStageException(PipelineStage stage, String message) {
        super(message);
        this.stage = stage;
    }

    public PipelineStage getStage() {
        return stage;
    }
}
