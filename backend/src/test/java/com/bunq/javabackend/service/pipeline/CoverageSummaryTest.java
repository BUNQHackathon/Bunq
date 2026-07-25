package com.bunq.javabackend.service.pipeline;

import com.bunq.javabackend.model.gap.Gap;
import com.bunq.javabackend.model.mapping.Mapping;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.bunq.javabackend.service.pipeline.GapCoverage.CoverageStatus.CONTROL_MISSING;
import static com.bunq.javabackend.service.pipeline.GapCoverage.CoverageStatus.JURISDICTION_DELTA;
import static com.bunq.javabackend.service.pipeline.GapCoverage.CoverageStatus.SATISFIED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoverageSummaryTest {

    private Gap gapWithSemantics(String obligationId, String semantics) {
        return Gap.builder()
                .id("gap-" + obligationId)
                .obligationId(obligationId)
                .metadata(semantics != null ? Map.of("gap_semantics", semantics) : Map.of())
                .build();
    }

    private Mapping mapping(String obligationId, String controlId, Double confidence) {
        return Mapping.builder()
                .id("map-" + obligationId + "-" + controlId)
                .obligationId(obligationId)
                .controlId(controlId)
                .mappingConfidence(confidence)
                .build();
    }

    @Test
    void parseStatus_readsGapSemanticsMetadata() {
        var gap = gapWithSemantics("o1", "jurisdiction_delta");
        assertEquals(JURISDICTION_DELTA, CoverageSummary.parseStatus(gap));
    }

    @Test
    void parseStatus_returnsNullForMissingOrUnrecognisedMetadata() {
        assertNull(CoverageSummary.parseStatus(gapWithSemantics("o1", null)));
        assertNull(CoverageSummary.parseStatus(gapWithSemantics("o1", "not_a_real_status")));
        assertNull(CoverageSummary.parseStatus(Gap.builder().id("g").obligationId("o1").build()));
        assertNull(CoverageSummary.parseStatus(null));
    }

    @Test
    void hasSemantics_trueWhenAtLeastOneGapClassified() {
        var gaps = List.of(gapWithSemantics("o1", null), gapWithSemantics("o2", "control_missing"));
        assertTrue(CoverageSummary.hasSemantics(gaps));
    }

    @Test
    void hasSemantics_falseForOlderSessionsWithNoClassification() {
        var gaps = List.of(gapWithSemantics("o1", null), gapWithSemantics("o2", null));
        assertFalse(CoverageSummary.hasSemantics(gaps));
    }

    @Test
    void countByStatus_countsGapSemanticsAndDerivesSatisfiedFromHighConfidenceMappings() {
        var gaps = List.of(
                gapWithSemantics("o2", "jurisdiction_delta"),
                gapWithSemantics("o3", "control_missing"),
                gapWithSemantics("o4", "control_missing")
        );
        var mappings = List.of(
                mapping("o1", "c1", 90.0),   // satisfied
                mapping("o1", "c2", 50.0),   // second mapping for same obligation, should not double count
                mapping("o2", "c3", 30.0),   // below 75, not satisfied (matches jurisdiction_delta gap)
                mapping("o5", "c4", 80.0)    // satisfied, distinct obligation
        );

        var counts = CoverageSummary.countByStatus(gaps, mappings);

        assertEquals(2, counts.get(SATISFIED));
        assertEquals(1, counts.get(JURISDICTION_DELTA));
        assertEquals(2, counts.get(CONTROL_MISSING));
    }

    @Test
    void countByStatus_handlesNullsDefensively() {
        var counts = CoverageSummary.countByStatus(null, null);
        assertTrue(counts.isEmpty());
    }
}
