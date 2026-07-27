package com.bunq.javabackend.service.pipeline;

import org.junit.jupiter.api.Test;

import static com.bunq.javabackend.service.pipeline.GapCoverage.CoverageStatus.CONTROL_MISSING;
import static com.bunq.javabackend.service.pipeline.GapCoverage.CoverageStatus.JURISDICTION_DELTA;
import static com.bunq.javabackend.service.pipeline.GapCoverage.CoverageStatus.NEEDS_REVIEW;
import static com.bunq.javabackend.service.pipeline.GapCoverage.CoverageStatus.PARTIAL;
import static com.bunq.javabackend.service.pipeline.GapCoverage.CoverageStatus.SATISFIED;
import static com.bunq.javabackend.service.pipeline.GapCoverage.CoverageStatus.SUBSTANTIALLY_COVERED;
import static org.junit.jupiter.api.Assertions.assertEquals;

class GapCoverageTest {

    @Test
    void satisfied_at_75() {
        assertEquals(SATISFIED, GapCoverage.classify(75.0, true, false, false));
    }

    @Test
    void substantially_covered_just_under_75() {
        assertEquals(SUBSTANTIALLY_COVERED, GapCoverage.classify(74.9, true, false, false));
    }

    @Test
    void substantially_covered_at_60() {
        assertEquals(SUBSTANTIALLY_COVERED, GapCoverage.classify(60.0, true, false, false));
    }

    @Test
    void partial_just_under_60() {
        assertEquals(PARTIAL, GapCoverage.classify(59.9, true, false, false));
    }

    @Test
    void partial_at_45() {
        assertEquals(PARTIAL, GapCoverage.classify(45.0, true, false, false));
    }

    @Test
    void jurisdiction_delta_just_under_45() {
        assertEquals(JURISDICTION_DELTA, GapCoverage.classify(44.9, true, false, false));
    }

    @Test
    void jurisdiction_delta_at_21() {
        assertEquals(JURISDICTION_DELTA, GapCoverage.classify(21.0, true, false, false));
    }

    @Test
    void control_missing_just_under_21() {
        assertEquals(CONTROL_MISSING, GapCoverage.classify(20.9, true, false, false));
    }

    @Test
    void control_missing_when_no_mapping() {
        assertEquals(CONTROL_MISSING, GapCoverage.classify(null, false, false, false));
    }

    @Test
    void needs_review_wins_over_high_confidence_when_retrieval_degraded() {
        assertEquals(NEEDS_REVIEW, GapCoverage.classify(90.0, true, true, false));
    }

    @Test
    void null_confidence_with_mapping_needs_review() {
        assertEquals(NEEDS_REVIEW, GapCoverage.classify(null, true, false, false));
    }

    @Test
    void noiseMappingIsNotReportedAsCoverage() {
        // Regression: a mapping the matcher itself calls "unrelated" (0-20) must never be
        // reported as evidence that a generic control exists.
        assertEquals(CONTROL_MISSING, GapCoverage.classify(5.0, true, false, false));
    }

    // ── jurisdiction-specifics-unmet (explicit judge signal) ────────────────────

    @Test
    void jurisdictionSpecificsUnmet_overrides_high_confidence_satisfied() {
        // GT-15 regression: a control that clearly addresses the same safeguard can score
        // >= 75 and still fail a jurisdiction-specific parameter (e.g. Banca d'Italia's
        // two-axis residual-risk matrix vs. a generic three-tier risk rating). The explicit
        // flag must override the confidence band, including SATISFIED.
        assertEquals(JURISDICTION_DELTA, GapCoverage.classify(95.0, true, false, true));
    }

    @Test
    void jurisdictionSpecificsUnmet_withoutAnyMapping_stillControlMissing() {
        // No mapping at all means there is nothing to be a "delta" against.
        assertEquals(CONTROL_MISSING, GapCoverage.classify(null, false, false, true));
    }

    @Test
    void jurisdictionSpecificsUnmetFalse_at80_stillSatisfied() {
        assertEquals(SATISFIED, GapCoverage.classify(80.0, true, false, false));
    }

    @Test
    void absentJurisdictionSpecificsFlag_fallsBackToExistingBands() {
        // false is the default when the model omitted the field / old session data —
        // behaviour must be identical to the pre-existing 3-arg classification.
        assertEquals(SATISFIED, GapCoverage.classify(75.0, true, false, false));
        assertEquals(JURISDICTION_DELTA, GapCoverage.classify(30.0, true, false, false));
        assertEquals(CONTROL_MISSING, GapCoverage.classify(10.0, true, false, false));
    }

    @Test
    void retrievalDegraded_stillWinsOverJurisdictionSpecificsUnmet() {
        assertEquals(NEEDS_REVIEW, GapCoverage.classify(95.0, true, true, true));
    }
}
