package com.bunq.javabackend.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditTrailEntryDTO {
    private String action;
    private Instant timestamp;
    private String actor;
    private String decision;
    private String prevHash;
}
