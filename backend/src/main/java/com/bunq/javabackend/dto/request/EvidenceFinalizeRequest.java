package com.bunq.javabackend.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvidenceFinalizeRequest {
    @NotBlank(message = "s3Key is required")
    private String s3Key;
    private String mappingId;
    @Size(max = 10000)
    private String description;
}
