package com.bunq.javabackend.controller;

import com.bunq.javabackend.dto.request.RagRequest;
import com.bunq.javabackend.dto.response.RagResponse;
import com.bunq.javabackend.service.RagService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/rag")
@RequiredArgsConstructor
public class RagController {

    private final RagService ragService;

    @PostMapping("/query")
    public RagResponse query(@Valid @RequestBody RagRequest req) {
        return ragService.query(req);
    }

    @PostMapping(path = "/query/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter queryStream(@Valid @RequestBody RagRequest req) {
        return ragService.queryStream(req);
    }
}
