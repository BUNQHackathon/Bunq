package com.bunq.javabackend.dto.response;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class CitationDTO {

    String kbType;
    String chunkId;
    Double score;
    String s3Uri;
    String sourceText;
}
