package com.bunq.javabackend.dto.response;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class TokenUsageDTO {

    Integer inputTokens;
    Integer outputTokens;
    Integer cacheReadTokens;
    Integer cacheCreationTokens;
}
