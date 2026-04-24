package com.bunq.javabackend.dto.response.sidecar;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GraphEdge {
    private String id;
    private String type;
    private String label;
    private Map<String, Object> data;
}
