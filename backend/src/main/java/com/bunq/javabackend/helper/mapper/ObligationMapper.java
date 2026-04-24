package com.bunq.javabackend.helper.mapper;

import com.bunq.javabackend.dto.response.ObligationResponseDTO;
import com.bunq.javabackend.dto.response.ObligationSourceDTO;
import com.bunq.javabackend.model.obligation.Obligation;
import com.bunq.javabackend.model.obligation.ObligationSource;

public class ObligationMapper {

    public static ObligationResponseDTO toDto(Obligation source) {
        return ObligationResponseDTO.builder()
                .id(source.getId())
                .source(toSourceDto(source.getSource()))
                .obligationType(source.getObligationType() != null ? source.getObligationType().name() : null)
                .deontic(source.getDeontic() != null ? source.getDeontic().name() : null)
                .subject(source.getSubject())
                .action(source.getAction())
                .conditions(source.getConditions())
                .riskCategory(source.getRiskCategory())
                .applicableJurisdictions(source.getApplicableJurisdictions())
                .applicableEntities(source.getApplicableEntities())
                .severity(source.getSeverity() != null ? source.getSeverity().name() : null)
                .regulatoryPenaltyRange(source.getRegulatoryPenaltyRange())
                .extractedAt(source.getExtractedAt())
                .extractionConfidence(source.getExtractionConfidence())
                .sessionId(source.getSessionId())
                .regulationId(source.getRegulationId())
                .build();
    }

    private static ObligationSourceDTO toSourceDto(ObligationSource src) {
        if (src == null) return null;
        return ObligationSourceDTO.builder()
                .regulation(src.getRegulation())
                .article(src.getArticle())
                .section(src.getSection())
                .paragraph(src.getParagraph())
                .sourceText(src.getSourceText())
                .retrievedFromKbChunkId(src.getRetrievedFromKbChunkId())
                .build();
    }
}
