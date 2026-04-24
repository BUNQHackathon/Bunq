package com.bunq.javabackend.dto.response.graph;

public record GraphNodeDTO(String id, String label, String cat, boolean doc, int size, String updated) {}
