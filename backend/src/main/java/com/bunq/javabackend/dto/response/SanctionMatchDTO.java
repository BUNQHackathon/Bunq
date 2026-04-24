package com.bunq.javabackend.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SanctionMatchDTO {
    private String listSource;
    private String entityName;
    private List<String> aliases;
    private Double matchScore;
    private Instant listVersionTimestamp;
}
