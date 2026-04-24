package com.bunq.javabackend.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExtractObligationsRequestDTO {
    private static final String UUID_REGEX = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";

    @NotBlank(message = "sessionId is required")
    @Pattern(regexp = UUID_REGEX, message = "sessionId must be a UUID")
    private String sessionId;
    @Valid
    private RegulationChunkDTO regulationChunk;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RegulationChunkDTO {
        private String regulation;
        private String article;
        private String paragraphId;
        @NotBlank(message = "regulationChunk.text is required")
        @Size(max = 50_000, message = "regulationChunk.text must not exceed 50000 characters")
        private String text;
    }
}
