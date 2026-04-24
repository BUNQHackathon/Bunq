package com.bunq.javabackend.dto.request;

import com.bunq.javabackend.dto.response.CounterpartyDTO;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScreenSanctionsRequestDTO {
    private static final String UUID_REGEX = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";

    @NotBlank(message = "sessionId is required")
    @Pattern(regexp = UUID_REGEX, message = "sessionId must be a UUID")
    private String sessionId;
    private List<CounterpartyDTO> counterparties;
    @Size(max = 10_000, message = "briefText must not exceed 10000 characters")
    private String briefText;
}
