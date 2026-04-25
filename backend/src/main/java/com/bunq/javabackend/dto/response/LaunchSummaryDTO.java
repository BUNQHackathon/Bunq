package com.bunq.javabackend.dto.response;

import com.bunq.javabackend.model.launch.LaunchKind;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LaunchSummaryDTO {
    private String id;
    private String name;
    private String license;
    private LaunchKind kind;
    private String status;
    private int counterpartiesCount;
    private int jurisdictionCount;
    private String aggregateVerdict;
    private String createdAt;
    private String updatedAt;
    private List<JurisdictionSummary> jurisdictions;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class JurisdictionSummary {
        private String code;
        private String verdict;
        private String status;
    }
}
