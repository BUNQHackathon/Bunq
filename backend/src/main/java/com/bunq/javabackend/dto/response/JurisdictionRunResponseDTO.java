package com.bunq.javabackend.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JurisdictionRunResponseDTO {
    private String launchId;
    private String jurisdictionCode;
    private String currentSessionId;
    private String verdict;
    private Integer gapsCount;
    private Integer sanctionsHits;
    private String proofPackS3Key;
    private String lastRunAt;
    private String status;
}
