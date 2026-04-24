package com.bunq.javabackend.dto.request;

import com.bunq.javabackend.dto.response.CounterpartyDTO;
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
public class PipelineStartRequestDTO {
    @Size(max = 500, message = "regulation must not exceed 500 characters")
    private String regulation;
    @Size(max = 500, message = "policy must not exceed 500 characters")
    private String policy;
    private List<CounterpartyDTO> counterparties;
    @Size(max = 10_000, message = "briefText must not exceed 10000 characters")
    private String briefText;
}
