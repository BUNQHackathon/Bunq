package com.bunq.javabackend.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecutiveSummaryDTO {
    private String overall;
    private int gapCount;
    private int obligationCount;
    private int controlCount;
    private List<String> topRisks;
    private String narrative;
}
