package com.bunq.javabackend.service.pipeline;

import java.util.concurrent.CompletableFuture;

public interface Stage {

    PipelineStage stage();

    CompletableFuture<Void> execute(PipelineContext ctx);
}
