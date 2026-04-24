package com.bunq.javabackend.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BedrockUsageDTO {
    private int cacheCreationInputTokens;
    private int cacheReadInputTokens;
    private int inputTokens;
    private int outputTokens;
}
