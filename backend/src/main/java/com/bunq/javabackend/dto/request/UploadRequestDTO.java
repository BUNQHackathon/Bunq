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
public class UploadRequestDTO {
    @NotBlank(message = "fileName is required")
    @Size(max = 255, message = "fileName must not exceed 255 characters")
    private String fileName;
    @NotBlank(message = "contentType is required")
    private String contentType;
    @NotBlank(message = "fileKind is required")
    private String fileKind;
}
