package com.bunq.javabackend.controller.documents;

import com.bunq.javabackend.dto.response.SessionDocumentsResponse;
import com.bunq.javabackend.service.documents.SessionDocumentsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/sessions")
@RequiredArgsConstructor
public class SessionDocumentsController {

    private final SessionDocumentsService sessionDocumentsService;

    @PostMapping("/{sessionId}/documents/{documentId}")
    public ResponseEntity<SessionDocumentsResponse> attachDocument(
            @PathVariable String sessionId,
            @PathVariable String documentId) {
        return ResponseEntity.ok(sessionDocumentsService.attach(sessionId, documentId));
    }

    @DeleteMapping("/{sessionId}/documents/{documentId}")
    public ResponseEntity<Void> detachDocument(
            @PathVariable String sessionId,
            @PathVariable String documentId) {
        sessionDocumentsService.detach(sessionId, documentId);
        return ResponseEntity.noContent().build();
    }
}
