package com.bunq.javabackend.dto.response.sidecar;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GraphDAG {
    private List<GraphNode> nodes;
    private List<GraphEdge> edges;
}
