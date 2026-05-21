package com.bunq.javabackend.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KeyGapDTO {
    private String text;
    private String gapId;
    private String obligationId;
    private ObligationSourceDTO source;
}
