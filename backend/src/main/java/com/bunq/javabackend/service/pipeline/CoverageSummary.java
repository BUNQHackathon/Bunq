package com.bunq.javabackend.service.pipeline;

import com.bunq.javabackend.model.gap.Gap;
import com.bunq.javabackend.model.mapping.Mapping;
import com.bunq.javabackend.service.pipeline.GapCoverage.CoverageStatus;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure counting/grouping helper for the coverage summary rendered in the executive and
 * findings PDFs. No Spring, no I/O — operates on already-fetched Gaps and Mappings for a
 * session so it can be unit-tested with hand-built objects.
 */
public final class CoverageSummary {

    private CoverageSummary() {}

    /** True if at least one gap carries a recognised {@code gap_semantics} metadata value. */
    public static boolean hasSemantics(List<Gap> gaps) {
        return gaps != null && gaps.stream().anyMatch(g -> parseStatus(g) != null);
    }

    /** Parses a Gap's {@code gap_semantics} metadata into a {@link CoverageStatus}, or null if absent/unrecognised. */
    public static CoverageStatus parseStatus(Gap gap) {
        if (gap == null || gap.getMetadata() == null) {
            return null;
        }
        String raw = gap.getMetadata().get("gap_semantics");
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return CoverageStatus.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Counts obligations per coverage status. Gap-derived statuses come from each gap's
     * {@code gap_semantics} metadata. SATISFIED obligations never produce a Gap, so that
     * count is derived separately as the number of distinct obligations with at least one
     * mapping whose confidence is &gt;= 75.
     */
    public static Map<CoverageStatus, Integer> countByStatus(List<Gap> gaps, List<Mapping> mappings) {
        Map<CoverageStatus, Integer> counts = new EnumMap<>(CoverageStatus.class);
        if (gaps != null) {
            for (Gap g : gaps) {
                CoverageStatus status = parseStatus(g);
                if (status != null) {
                    counts.merge(status, 1, Integer::sum);
                }
            }
        }
        Set<String> satisfiedObligationIds = new HashSet<>();
        if (mappings != null) {
            for (Mapping m : mappings) {
                if (m.getObligationId() != null && m.getMappingConfidence() != null && m.getMappingConfidence() >= 75) {
                    satisfiedObligationIds.add(m.getObligationId());
                }
            }
        }
        if (!satisfiedObligationIds.isEmpty()) {
            counts.put(CoverageStatus.SATISFIED, satisfiedObligationIds.size());
        }
        return counts;
    }
}
