package com.bunq.javabackend.service.pipeline;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public enum PipelineStage {
    INGEST(0, "Ingest documents"),
    EXTRACT_OBLIGATIONS(1, "Extract obligations"),
    EXTRACT_CONTROLS(2, "Extract controls"),
    SANCTIONS_SCREEN(3, "Screen sanctions"),
    MAP_OBLIGATIONS_CONTROLS(4, "Map obligations to controls"),
    GAP_ANALYZE(5, "Analyze gaps"),
    GROUND_CHECK(6, "Ground-check citations"),
    NARRATE(7, "Generate narrative");

    @Getter
    private final int ordinal;

    @Getter
    private final String label;

    public static int totalStages() {
        return values().length;
    }
}
