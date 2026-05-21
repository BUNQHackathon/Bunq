package com.bunq.javabackend.helper.mapper;

import com.bunq.javabackend.dto.response.GapResponseDTO;
import com.bunq.javabackend.dto.response.RecommendedActionDTO;
import com.bunq.javabackend.dto.response.SeverityDimensionsDTO;
import com.bunq.javabackend.helper.GapNarrative;
import com.bunq.javabackend.model.gap.Gap;
import com.bunq.javabackend.model.gap.RecommendedAction;
import com.bunq.javabackend.model.gap.SeverityDimensions;

import java.util.List;

public class GapMapper {

    public static GapResponseDTO toDto(Gap source) {
        return GapResponseDTO.builder()
                .id(source.getId())
                .obligationId(source.getObligationId())
                .gapType(source.getGapType() != null ? source.getGapType().name() : null)
                .gapStatus(source.getGapStatus() != null ? source.getGapStatus().name() : null)
                .severityDimensions(toDimensionsDto(source.getSeverityDimensions()))
                .recommendedActions(toActionDtos(effectiveActions(source)))
                .remediationDeadline(source.getRemediationDeadline())
                .escalationRequired(source.getEscalationRequired())
                .narrative(GapNarrative.clean(source.getNarrative()))
                .sessionId(source.getSessionId())
                .build();
    }

    private static List<RecommendedAction> effectiveActions(Gap source) {
        List<RecommendedAction> stored = source.getRecommendedActions();
        if (stored != null && !stored.isEmpty()) return stored;
        return GapNarrative.recoverActions(source.getNarrative());
    }

    private static SeverityDimensionsDTO toDimensionsDto(SeverityDimensions dims) {
        if (dims == null) return null;
        return SeverityDimensionsDTO.builder()
                .regulatoryUrgency(dims.getRegulatoryUrgency())
                .penaltySeverity(dims.getPenaltySeverity())
                .probability(dims.getProbability())
                .businessImpact(dims.getBusinessImpact())
                .combinedRiskScore(dims.getCombinedRiskScore())
                .build();
    }

    private static List<RecommendedActionDTO> toActionDtos(List<RecommendedAction> actions) {
        if (actions == null) return null;
        return actions.stream().map(a -> RecommendedActionDTO.builder()
                .action(a.getAction())
                .priority(a.getPriority() != null ? a.getPriority().name() : null)
                .effortDays(a.getEffortDays())
                .suggestedOwner(a.getSuggestedOwner())
                .build()).toList();
    }
}
