package com.bunq.javabackend.dto.response.events;

import java.time.Instant;

public abstract class PipelineEvent {

    public abstract String getSessionId();

    public abstract Instant getTimestamp();

    public abstract String getType();
}
