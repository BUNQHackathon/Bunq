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
public class AuditLogResponseDTO {
    private String id;
    private String sessionId;
    private String mappingId;
    private String action;
    private String actor;
    private Instant timestamp;
    private String prevHash;
    private String entryHash;
}
