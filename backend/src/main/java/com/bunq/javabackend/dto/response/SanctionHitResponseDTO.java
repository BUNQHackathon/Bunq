package com.bunq.javabackend.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SanctionHitResponseDTO {
    private String id;
    private String sessionId;
    private CounterpartyDTO counterparty;
    private String matchStatus;
    private List<SanctionMatchDTO> hits;
    private Map<String, String> entityMetadata;
    private Instant screenedAt;
}
