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
public class LaunchResponseDTO {
    private String id;
    private String name;
    private String brief;
    private String license;
    private List<String> counterparties;
    private String status;
    private String createdAt;
    private String updatedAt;
    private List<JurisdictionRunResponseDTO> jurisdictions;
}
