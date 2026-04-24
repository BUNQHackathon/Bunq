package com.bunq.javabackend.controller;

import com.bunq.javabackend.dto.request.ChatWithGraphRequestDTO;
import com.bunq.javabackend.service.ChatWithGraphService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/chat")
@RequiredArgsConstructor
public class ChatWithGraphController {
    private final ChatWithGraphService chatWithGraphService;

    @PostMapping(value = "/with-graph", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatWithGraph(@Valid @RequestBody ChatWithGraphRequestDTO request) {
        return chatWithGraphService.startChat(request);
    }
}
