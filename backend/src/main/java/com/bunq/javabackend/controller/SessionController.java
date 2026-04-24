package com.bunq.javabackend.controller;

import com.bunq.javabackend.dto.request.CreateSessionRequestDTO;
import com.bunq.javabackend.dto.response.SessionResponseDTO;
import com.bunq.javabackend.service.SessionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/sessions")
@RequiredArgsConstructor
public class SessionController {

    private final SessionService sessionService;

    @GetMapping
    public ResponseEntity<List<SessionResponseDTO>> listSessions() {
        return ResponseEntity.ok(sessionService.listSessions());
    }

    @PostMapping
    public ResponseEntity<SessionResponseDTO> createSession(
            @Valid @RequestBody(required = false) CreateSessionRequestDTO request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(sessionService.createSession(request));
    }

    @GetMapping("/{id}")
    public ResponseEntity<SessionResponseDTO> getSession(@PathVariable String id) {
        return ResponseEntity.ok(sessionService.getSession(id));
    }
}
