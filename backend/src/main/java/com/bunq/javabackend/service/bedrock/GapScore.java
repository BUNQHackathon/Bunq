package com.bunq.javabackend.service.bedrock;

import com.bunq.javabackend.model.gap.RecommendedAction;
import com.bunq.javabackend.model.gap.SeverityDimensions;
import java.util.List;

public record GapScore(
        String narrative,
        boolean escalationRequired,
        Double severity,
        Double likelihood,
        Double detectability,
        Double blastRadius,
        Double recoverability,
        double residualRisk,
        SeverityDimensions severityDimensions,
        List<RecommendedAction> recommendedActions
) {}
