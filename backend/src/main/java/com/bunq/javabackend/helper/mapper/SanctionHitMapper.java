package com.bunq.javabackend.helper.mapper;

import com.bunq.javabackend.dto.response.CounterpartyDTO;
import com.bunq.javabackend.dto.response.SanctionHitResponseDTO;
import com.bunq.javabackend.dto.response.SanctionMatchDTO;
import com.bunq.javabackend.model.sanction.Counterparty;
import com.bunq.javabackend.model.sanction.SanctionHit;
import com.bunq.javabackend.model.sanction.SanctionMatch;

import java.util.List;

public class SanctionHitMapper {

    public static SanctionHitResponseDTO toDto(SanctionHit source) {
        return SanctionHitResponseDTO.builder()
                .id(source.getId())
                .sessionId(source.getSessionId())
                .counterparty(toCounterpartyDto(source.getCounterparty()))
                .matchStatus(source.getMatchStatus() != null ? source.getMatchStatus().name() : null)
                .hits(toMatchDtos(source.getHits()))
                .entityMetadata(source.getEntityMetadata())
                .screenedAt(source.getScreenedAt())
                .build();
    }

    private static CounterpartyDTO toCounterpartyDto(Counterparty cp) {
        if (cp == null) return null;
        return CounterpartyDTO.builder()
                .name(cp.getName())
                .country(cp.getCountry())
                .type(cp.getType() != null ? cp.getType().name() : null)
                .build();
    }

    private static List<SanctionMatchDTO> toMatchDtos(List<SanctionMatch> matches) {
        if (matches == null) return null;
        return matches.stream().map(m -> SanctionMatchDTO.builder()
                .listSource(m.getListSource())
                .entityName(m.getEntityName())
                .aliases(m.getAliases())
                .matchScore(m.getMatchScore())
                .listVersionTimestamp(m.getListVersionTimestamp())
                .build()).toList();
    }
}
