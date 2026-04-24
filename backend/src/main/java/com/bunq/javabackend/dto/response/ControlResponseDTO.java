package com.bunq.javabackend.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ControlResponseDTO {
    private String id;
    private String controlType;
    private String category;
    private String description;
    private String owner;
    private String testingCadence;
    private String evidenceType;
    private LocalDate lastTested;
    private String testingStatus;
    private String implementationStatus;
    private List<String> mappedStandards;
    private List<String> linkedTools;
    private ControlSourceRefDTO sourceDocRef;
    private String sessionId;
    private String bankId;
}
