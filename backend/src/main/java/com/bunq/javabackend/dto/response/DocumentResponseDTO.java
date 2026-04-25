package com.bunq.javabackend.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Set;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentResponseDTO {
    private String id;
    private String filename;
    private String displayName;
    private String contentType;
    private Long sizeBytes;
    private String kind;
    private Set<String> jurisdictions;
    private Instant firstSeenAt;
    private Instant lastUsedAt;
    private String extractedText;
    private Instant extractedAt;
    private Integer pageCount;
    private boolean obligationsExtracted;
    private boolean controlsExtracted;
}
