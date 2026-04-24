package com.bunq.javabackend.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ObligationSourceDTO {
    private String regulation;
    private String article;
    private String section;
    private Integer paragraph;
    private String sourceText;
    private String retrievedFromKbChunkId;
}
