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
public class ChatWithGraphRequestDTO {
    @NotBlank
    private String question;
    private String jurisdictionHint;   // optional ISO-2; overrides inferFromText if set
}
