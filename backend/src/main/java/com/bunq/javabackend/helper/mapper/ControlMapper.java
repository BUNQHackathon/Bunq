package com.bunq.javabackend.helper.mapper;

import com.bunq.javabackend.dto.response.ControlResponseDTO;
import com.bunq.javabackend.dto.response.ControlSourceRefDTO;
import com.bunq.javabackend.model.control.Control;
import com.bunq.javabackend.model.control.ControlSourceRef;

public class ControlMapper {

    public static ControlResponseDTO toDto(Control source) {
        return ControlResponseDTO.builder()
                .id(source.getId())
                .controlType(source.getControlType() != null ? source.getControlType().name() : null)
                .category(source.getCategory() != null ? source.getCategory().name() : null)
                .description(source.getDescription())
                .owner(source.getOwner())
                .testingCadence(source.getTestingCadence())
                .evidenceType(source.getEvidenceType())
                .lastTested(source.getLastTested())
                .testingStatus(source.getTestingStatus() != null ? source.getTestingStatus().name() : null)
                .implementationStatus(source.getImplementationStatus() != null ? source.getImplementationStatus().name() : null)
                .mappedStandards(source.getMappedStandards())
                .linkedTools(source.getLinkedTools())
                .sourceDocRef(toSourceRefDto(source.getSourceDocRef()))
                .sessionId(source.getSessionId())
                .bankId(source.getBankId())
                .build();
    }

    private static ControlSourceRefDTO toSourceRefDto(ControlSourceRef ref) {
        if (ref == null) return null;
        return ControlSourceRefDTO.builder()
                .bank(ref.getBank())
                .doc(ref.getDoc())
                .sectionId(ref.getSectionId())
                .kbChunkId(ref.getKbChunkId())
                .build();
    }
}
