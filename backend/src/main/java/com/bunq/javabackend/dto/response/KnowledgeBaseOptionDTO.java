package com.bunq.javabackend.dto.response;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class KnowledgeBaseOptionDTO {

    String key;
    String label;
    String knowledgeBaseId;
    String kbType;
    Boolean defaultOption;
}
