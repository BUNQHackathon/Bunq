package com.bunq.javabackend.controller.session;

import com.bunq.javabackend.dto.request.CreateSessionRequestDTO;
import com.bunq.javabackend.dto.response.SessionResponseDTO;
import com.bunq.javabackend.service.session.SessionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/sessions")
@RequiredArgsConstructor
public class SessionController {

    private final SessionService sessionService;

    @PostMapping
    public ResponseEntity<SessionResponseDTO> createSession(
            @Valid @RequestBody(required = false) CreateSessionRequestDTO request) {
        CreateSessionRequestDTO dto = request != null ? request : new CreateSessionRequestDTO();
        return ResponseEntity.status(HttpStatus.CREATED).body(sessionService.createSession(dto));
    }

    @GetMapping("/{id}")
    public ResponseEntity<SessionResponseDTO> getSession(@PathVariable String id) {
        return ResponseEntity.ok(sessionService.getSession(id));
    }
}
