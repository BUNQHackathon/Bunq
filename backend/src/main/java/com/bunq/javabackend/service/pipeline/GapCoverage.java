package com.bunq.javabackend.service.pipeline;

/**
 * Deterministic confidence-band classifier for obligation coverage.
 * No LLM, no AWS — pure static logic used to replace the old binary
 * "gap vs no gap" heuristic with a finer-grained set of coverage bands.
 */
public final class GapCoverage {

    private GapCoverage() {}

    public enum CoverageStatus {
        SATISFIED,
        SUBSTANTIALLY_COVERED,
        PARTIAL,
        JURISDICTION_DELTA,
        CONTROL_MISSING,
        NEEDS_REVIEW
    }

    /**
     * Classifies an obligation's coverage from its BEST (max) mapping confidence.
     *
     * The confidence bands are calibrated to match the matcher's own scoring anchors
     * (see {@code SystemPrompts.MATCH_OBLIGATIONS_TO_CONTROLS}, ~lines 75-79):
     * 0-20 unrelated, 21-39 tangentially related, 40-60 partially addresses,
     * 61-79 substantially addresses, 80-100 directly and fully addresses.
     * A mapping the matcher itself calls "unrelated" (0-20) must never be reported as
     * evidence that a generic control exists — that would be a false claim of coverage.
     *
     * Bands (evaluated in this order):
     * <ul>
     *   <li>{@code retrievalDegraded == true} → {@link CoverageStatus#NEEDS_REVIEW} — never claim
     *       "missing" when the retrieval/search that produced the mappings was degraded.</li>
     *   <li>{@code !hasAnyMapping} → {@link CoverageStatus#CONTROL_MISSING}</li>
     *   <li>{@code hasAnyMapping && bestConfidence == null} → {@link CoverageStatus#NEEDS_REVIEW} —
     *       a mapping exists but its confidence was never recorded; that is unknown, not evidence
     *       of partial coverage, so we don't guess in the direction that flatters coverage.</li>
     *   <li>{@code bestConfidence >= 75} → {@link CoverageStatus#SATISFIED}</li>
     *   <li>{@code 60 <= bestConfidence < 75} → {@link CoverageStatus#SUBSTANTIALLY_COVERED}</li>
     *   <li>{@code 45 <= bestConfidence < 60} → {@link CoverageStatus#PARTIAL}</li>
     *   <li>{@code 21 <= bestConfidence < 45} → {@link CoverageStatus#JURISDICTION_DELTA} —
     *       "tangentially related" per the matcher's own anchors: a related control plausibly
     *       exists but only loosely matches; typically the generic control is present and an
     *       Italy-specific parameter/channel/format is what's missing. The exact delta text is
     *       refined later by the narrative layer.</li>
     *   <li>{@code bestConfidence < 21} → {@link CoverageStatus#CONTROL_MISSING} — "unrelated"
     *       per the matcher's own anchors; a noise-level mapping is effectively no control.</li>
     * </ul>
     */
    public static CoverageStatus classify(Double bestConfidence, boolean hasAnyMapping, boolean retrievalDegraded) {
        if (retrievalDegraded) {
            return CoverageStatus.NEEDS_REVIEW;
        }
        if (!hasAnyMapping) {
            return CoverageStatus.CONTROL_MISSING;
        }
        if (bestConfidence == null) {
            return CoverageStatus.NEEDS_REVIEW;
        }
        double best = bestConfidence;
        if (best >= 75) {
            return CoverageStatus.SATISFIED;
        }
        if (best >= 60) {
            return CoverageStatus.SUBSTANTIALLY_COVERED;
        }
        if (best >= 45) {
            return CoverageStatus.PARTIAL;
        }
        if (best >= 21) {
            return CoverageStatus.JURISDICTION_DELTA;
        }
        return CoverageStatus.CONTROL_MISSING;
    }
}
