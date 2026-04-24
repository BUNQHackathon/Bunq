package com.bunq.javabackend.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateLaunchRequestDTO {
    @NotBlank
    private String name;
    @NotBlank
    private String brief;
    private String license;
    private List<String> markets;
}
