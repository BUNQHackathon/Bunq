package com.bunq.javabackend.dto.response;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class CitationDTO {

    String kbType;
    String knowledgeBaseId;
    String knowledgeBaseLabel;
    String chunkId;
    Double score;
    String s3Uri;
    String displayName;
    String documentId;
    String sha256;
    String sourceText;
}
