package com.bunq.javabackend.dto.response;

import com.bunq.javabackend.model.enums.SessionState;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionResponseDTO {
    private String id;
    private SessionState state;
    private String regulation;
    private String policy;
    private List<String> counterparties;
    private List<String> documentIds;
    private String verdict;
    private String errorMessage;
    private String createdAt;
    private String updatedAt;
}
