package com.bunq.javabackend.dto.request;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateSessionRequestDTO {
    @Size(max = 500, message = "regulation must not exceed 500 characters")
    private String regulation;
    @Size(max = 500, message = "policy must not exceed 500 characters")
    private String policy;
}
