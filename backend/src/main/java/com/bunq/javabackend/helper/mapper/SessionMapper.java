package com.bunq.javabackend.helper.mapper;

import com.bunq.javabackend.dto.request.CreateSessionRequestDTO;
import com.bunq.javabackend.dto.response.SessionResponseDTO;
import com.bunq.javabackend.model.session.Session;
import com.bunq.javabackend.model.enums.SessionState;
import com.bunq.javabackend.util.IdGenerator;

import java.time.Instant;

public class SessionMapper {

    public static SessionResponseDTO toDto(Session source) {
        return SessionResponseDTO.builder()
                .id(source.getId())
                .state(source.getState())
                .regulation(source.getRegulation())
                .policy(source.getPolicy())
                .counterparties(source.getCounterparties())
                .documentIds(source.getDocumentIds())
                .verdict(source.getVerdict())
                .errorMessage(source.getErrorMessage())
                .createdAt(source.getCreatedAt())
                .updatedAt(source.getUpdatedAt())
                .build();
    }

    public static Session toModel(CreateSessionRequestDTO dto) {
        String now = Instant.now().toString();
        return Session.builder()
                .id(IdGenerator.generateSessionId())
                .state(SessionState.CREATED)
                .regulation(dto != null ? dto.getRegulation() : null)
                .policy(dto != null ? dto.getPolicy() : null)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }
}
