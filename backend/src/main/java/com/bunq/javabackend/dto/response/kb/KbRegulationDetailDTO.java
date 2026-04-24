package com.bunq.javabackend.dto.response.kb;

import java.util.List;

public record KbRegulationDetailDTO(
    String id,
    String title,
    String category,
    String jurisdiction,
    String updated,
    String downloadUrl,
    List<KbSectionDTO> sections
) {}
