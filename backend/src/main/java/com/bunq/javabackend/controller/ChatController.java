package com.bunq.javabackend.controller;

import com.bunq.javabackend.dto.request.ChatRequestDTO;
import com.bunq.javabackend.dto.response.ChatHistoryResponseDTO;
import com.bunq.javabackend.dto.response.ChatSummaryResponseDTO;
import com.bunq.javabackend.service.ChatService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@RestController
@RequestMapping("/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter startChat(@Valid @RequestBody ChatRequestDTO req) {
        return chatService.startChat(req);
    }

    @GetMapping("/{chatId}/history")
    public ResponseEntity<ChatHistoryResponseDTO> history(@PathVariable String chatId) {
        return ResponseEntity.ok(chatService.getHistory(chatId));
    }

    @GetMapping
    public ResponseEntity<List<ChatSummaryResponseDTO>> listChats(
            @RequestParam(name = "limit", defaultValue = "100") int limit) {
        return ResponseEntity.ok(chatService.listChats(Math.min(Math.max(limit, 1), 500)));
    }
}
