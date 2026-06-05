package com.bunq.javabackend.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RunJurisdictionRequestDTO {
    private List<String> documentIds; // null or empty → autoDocService fallback
    private Boolean applyRelevanceFilter; // null treated as true (default on)
}
