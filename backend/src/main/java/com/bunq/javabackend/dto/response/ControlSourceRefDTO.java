package com.bunq.javabackend.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ControlSourceRefDTO {
    private String bank;
    private String doc;
    private String sectionId;
    private String kbChunkId;
}
