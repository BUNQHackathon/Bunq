package com.bunq.javabackend.dto.response.events;

import com.bunq.javabackend.service.pipeline.PipelineStage;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;

@Value
@Builder
public class StageStartedEvent extends PipelineEvent {

    String sessionId;
    Instant timestamp;
    PipelineStage stage;
    int ordinal;
    int totalStages;

    @Override
    public String getType() {
        return "stage.started";
    }
}
