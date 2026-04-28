package com.bunq.javabackend.helper.mapper;

import com.bunq.javabackend.dto.response.JurisdictionRunResponseDTO;
import com.bunq.javabackend.dto.response.LaunchResponseDTO;
import com.bunq.javabackend.dto.response.LaunchSummaryDTO;
import com.bunq.javabackend.dto.response.LaunchSummaryDTO.JurisdictionSummary;
import com.bunq.javabackend.model.enums.RunStatus;
import com.bunq.javabackend.model.launch.JurisdictionRun;
import com.bunq.javabackend.model.launch.Launch;

import java.util.List;

public class LaunchMapper {

    public static LaunchSummaryDTO toSummary(Launch l, int jurisdictionCount, String aggregateVerdict,
            List<JurisdictionSummary> jurisdictions) {
        return LaunchSummaryDTO.builder()
                .id(l.getId())
                .name(l.getName())
                .license(l.getLicense())
                .kind(l.getKind())
                .status(l.getStatus() != null ? l.getStatus().name() : null)
                .counterpartiesCount(l.getCounterparties() == null ? 0 : l.getCounterparties().size())
                .jurisdictionCount(jurisdictionCount)
                .aggregateVerdict(aggregateVerdict)
                .createdAt(l.getCreatedAt())
                .updatedAt(l.getUpdatedAt())
                .jurisdictions(jurisdictions)
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
                .status(l.getStatus() != null ? l.getStatus().name() : null)
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
                .status(r.getStatus() != null ? r.getStatus().name() : null)
                .failedStage(r.getFailedStage())
                .lastError(r.getLastError())
                .proofPackAvailable(r.getProofPackS3Key() != null)
                .build();
    }

    public static JurisdictionRunResponseDTO toDto(JurisdictionRun r, String verdict, String summary,
            List<String> requiredChanges, List<String> blockers, boolean proofPackAvailable,
            Integer regulationsCovered, Integer obligationsCount, Integer controlsCount) {
        return JurisdictionRunResponseDTO.builder()
                .launchId(r.getLaunchId())
                .jurisdictionCode(r.getJurisdictionCode())
                .currentSessionId(r.getCurrentSessionId())
                .verdict(verdict)
                .gapsCount(r.getGapsCount())
                .sanctionsHits(r.getSanctionsHits())
                .proofPackS3Key(r.getProofPackS3Key())
                .lastRunAt(r.getLastRunAt())
                .status(r.getStatus() != null ? r.getStatus().name() : null)
                .failedStage(r.getFailedStage())
                .lastError(r.getLastError())
                .summary(summary)
                .requiredChanges(requiredChanges)
                .blockers(blockers)
                .proofPackAvailable(proofPackAvailable)
                .regulationsCovered(regulationsCovered)
                .obligationsCount(obligationsCount)
                .controlsCount(controlsCount)
                .build();
    }
}
