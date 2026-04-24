package com.bunq.javabackend.controller;

import com.bunq.javabackend.dto.response.graph.GraphDataDTO;
import com.bunq.javabackend.service.GraphService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/graph")
@RequiredArgsConstructor
public class GraphController {

    private final GraphService graphService;

    @GetMapping
    public GraphDataDTO get() {
        return graphService.build();
    }
}
