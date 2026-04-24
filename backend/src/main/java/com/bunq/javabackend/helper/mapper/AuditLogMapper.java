package com.bunq.javabackend.helper.mapper;

import com.bunq.javabackend.dto.response.AuditLogResponseDTO;
import com.bunq.javabackend.model.audit.AuditLogEntry;

public class AuditLogMapper {

    public static AuditLogResponseDTO toDto(AuditLogEntry source) {
        return AuditLogResponseDTO.builder()
                .id(source.getId())
                .sessionId(source.getSessionId())
                .mappingId(source.getMappingId())
                .action(source.getAction())
                .actor(source.getActor())
                .timestamp(source.getTimestamp())
                .prevHash(source.getPrevHash())
                .entryHash(source.getEntryHash())
                .build();
    }
}
