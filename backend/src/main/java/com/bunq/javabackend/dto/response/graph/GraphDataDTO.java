package com.bunq.javabackend.dto.response.graph;

import java.util.List;

public record GraphDataDTO(List<GraphNodeDTO> nodes, List<GraphLinkDTO> links) {}
