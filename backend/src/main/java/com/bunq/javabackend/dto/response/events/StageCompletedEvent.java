package com.bunq.javabackend.dto.response.events;

import com.bunq.javabackend.service.pipeline.PipelineStage;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;

@Value
@Builder
public class StageCompletedEvent extends PipelineEvent {

    String sessionId;
    Instant timestamp;
    PipelineStage stage;
    long durationMs;
    int itemsProduced;

    @Override
    public String getType() {
        return "stage.complete";
    }
}
