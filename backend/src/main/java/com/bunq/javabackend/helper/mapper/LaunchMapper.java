package com.bunq.javabackend.helper.mapper;

import com.bunq.javabackend.dto.response.JurisdictionRunResponseDTO;
import com.bunq.javabackend.dto.response.LaunchResponseDTO;
import com.bunq.javabackend.dto.response.LaunchSummaryDTO;
import com.bunq.javabackend.model.launch.JurisdictionRun;
import com.bunq.javabackend.model.launch.Launch;

import java.util.List;

public class LaunchMapper {

    public static LaunchSummaryDTO toSummary(Launch l, int jurisdictionCount, String aggregateVerdict) {
        return LaunchSummaryDTO.builder()
                .id(l.getId())
                .name(l.getName())
                .license(l.getLicense())
                .kind(l.getKind())
                .status(l.getStatus())
                .counterpartiesCount(l.getCounterparties() == null ? 0 : l.getCounterparties().size())
                .jurisdictionCount(jurisdictionCount)
                .aggregateVerdict(aggregateVerdict)
                .createdAt(l.getCreatedAt())
                .updatedAt(l.getUpdatedAt())
                .build();
    }

    public static LaunchResponseDTO toDto(Launch l, List<JurisdictionRunResponseDTO> jurisdictions) {
        return LaunchResponseDTO.builder()
                .id(l.getId())
                .name(l.getName())
                .brief(l.getBrief())
                .license(l.getLicense())
                .kind(l.getKind())
                .counterparties(l.getCounterparties())
                .status(l.getStatus())
                .createdAt(l.getCreatedAt())
                .updatedAt(l.getUpdatedAt())
                .jurisdictions(jurisdictions)
                .build();
    }

    public static JurisdictionRunResponseDTO toDto(JurisdictionRun r) {
        return JurisdictionRunResponseDTO.builder()
                .launchId(r.getLaunchId())
                .jurisdictionCode(r.getJurisdictionCode())
                .currentSessionId(r.getCurrentSessionId())
                .verdict(r.getVerdict())
                .gapsCount(r.getGapsCount())
                .sanctionsHits(r.getSanctionsHits())
                .proofPackS3Key(r.getProofPackS3Key())
                .lastRunAt(r.getLastRunAt())
                .status(r.getStatus())
                .proofPackAvailable(r.getProofPackS3Key() != null)
                .build();
    }

    public static JurisdictionRunResponseDTO toDto(JurisdictionRun r, String summary,
            List<String> requiredChanges, List<String> blockers, boolean proofPackAvailable,
            Integer regulationsCovered) {
        return JurisdictionRunResponseDTO.builder()
                .launchId(r.getLaunchId())
                .jurisdictionCode(r.getJurisdictionCode())
                .currentSessionId(r.getCurrentSessionId())
                .verdict(r.getVerdict())
                .gapsCount(r.getGapsCount())
                .sanctionsHits(r.getSanctionsHits())
                .proofPackS3Key(r.getProofPackS3Key())
                .lastRunAt(r.getLastRunAt())
                .status(r.getStatus())
                .summary(summary)
                .requiredChanges(requiredChanges)
                .blockers(blockers)
                .proofPackAvailable(proofPackAvailable)
                .regulationsCovered(regulationsCovered)
                .build();
    }
}
