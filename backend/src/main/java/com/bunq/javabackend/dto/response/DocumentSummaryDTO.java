package com.bunq.javabackend.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentSummaryDTO {
    private String id;
    private String filename;
    private String contentType;
    private Long sizeBytes;
    private String kind;
    private Instant firstSeenAt;
    private Instant lastUsedAt;
    private Instant extractedAt;
    private Integer pageCount;
    private boolean obligationsExtracted;
    private boolean controlsExtracted;
}
