package com.bunq.javabackend.dto.response;

import lombok.Builder;

@Builder
public record JurisdictionOverviewDTO(
        String code,
        String aggregateVerdict,
        int launchCount,
        String worstVerdict
) {}
