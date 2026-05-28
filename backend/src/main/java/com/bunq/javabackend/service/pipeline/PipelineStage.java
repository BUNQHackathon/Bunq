package com.bunq.javabackend.service.pipeline;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public enum PipelineStage {
    INGEST(0, "Ingest documents"),
    EXTRACT_OBLIGATIONS(1, "Extract obligations"),
    EXTRACT_CONTROLS(2, "Extract controls"),
    FILTER_OBLIGATIONS(3, "Filter obligations by relevance"),
    SANCTIONS_SCREEN(4, "Screen sanctions"),
    MAP_OBLIGATIONS_CONTROLS(5, "Map obligations to controls"),
    GAP_ANALYZE(6, "Analyze gaps"),
    GROUND_CHECK(7, "Ground-check citations"),
    NARRATE(8, "Generate narrative");

    @Getter
    private final int ordinal;

    @Getter
    private final String label;

    public static int totalStages() {
        return values().length;
    }
}
