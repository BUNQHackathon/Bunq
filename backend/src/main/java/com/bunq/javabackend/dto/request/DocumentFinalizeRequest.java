package com.bunq.javabackend.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentFinalizeRequest {
    @NotBlank(message = "incomingKey is required")
    private String incomingKey;
    @NotBlank(message = "filename is required")
    private String filename;
    @NotBlank(message = "contentType is required")
    private String contentType;
    @NotBlank(message = "kind is required")
    private String kind;
}
