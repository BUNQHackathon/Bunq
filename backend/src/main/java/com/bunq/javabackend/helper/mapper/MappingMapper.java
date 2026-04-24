package com.bunq.javabackend.helper.mapper;

import com.bunq.javabackend.dto.response.MappingResponseDTO;
import com.bunq.javabackend.model.mapping.Mapping;

public class MappingMapper {

    public static MappingResponseDTO toDto(Mapping source) {
        return MappingResponseDTO.builder()
                .id(source.getId())
                .obligationId(source.getObligationId())
                .controlId(source.getControlId())
                .mappingConfidence(source.getMappingConfidence())
                .mappingType(source.getMappingType() != null ? source.getMappingType().name() : null)
                .gapStatus(source.getGapStatus() != null ? source.getGapStatus().name() : null)
                .semanticReason(source.getSemanticReason())
                .structuralMatchTags(source.getStructuralMatchTags())
                .evidenceLinks(source.getEvidenceLinks())
                .reviewerNotes(source.getReviewerNotes())
                .lastReviewed(source.getLastReviewed())
                .sessionId(source.getSessionId())
                .build();
    }
}
