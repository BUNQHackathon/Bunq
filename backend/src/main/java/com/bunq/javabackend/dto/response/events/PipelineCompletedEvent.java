package com.bunq.javabackend.dto.response.events;

import com.bunq.javabackend.dto.response.ExecutiveSummaryDTO;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;

@Value
@Builder
public class PipelineCompletedEvent extends PipelineEvent {

    String sessionId;
    Instant timestamp;
    ExecutiveSummaryDTO summary;
    String reportUrl;

    @Override
    public String getType() {
        return "done";
    }
}
