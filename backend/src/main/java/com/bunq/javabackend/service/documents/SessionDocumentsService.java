package com.bunq.javabackend.service.documents;

import com.bunq.javabackend.dto.response.SessionDocumentsResponse;
import com.bunq.javabackend.exception.NotFoundException;
import com.bunq.javabackend.exception.SessionNotFoundException;
import com.bunq.javabackend.model.session.Session;
import com.bunq.javabackend.repository.DocumentRepository;
import com.bunq.javabackend.repository.SessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SessionDocumentsService {

    private final SessionRepository sessionRepository;
    private final DocumentRepository documentRepository;

    public SessionDocumentsResponse attach(String sessionId, String documentId) {
        // Verify session exists
        Session session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new SessionNotFoundException(sessionId));

        // Verify document exists before any mutation (prevents phantom touchLastUsed)
        documentRepository.findById(documentId)
                .orElseThrow(() -> new NotFoundException("Document not found: " + documentId));

        // Atomically append documentId (no-op if already attached)
        sessionRepository.attachDocument(sessionId, documentId);

        // Safe: document existence verified above
        documentRepository.touchLastUsed(documentId, Instant.now());

        // Return the current document list from the session we already read
        List<String> documentIds = session.getDocumentIds() != null
                ? new ArrayList<>(session.getDocumentIds())
                : new ArrayList<>();
        if (!documentIds.contains(documentId)) {
            documentIds.add(documentId);
        }

        return SessionDocumentsResponse.builder()
                .sessionId(sessionId)
                .documentIds(documentIds)
                .build();
    }

    public void detach(String sessionId, String documentId) {
        Session session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new SessionNotFoundException(sessionId));

        List<String> documentIds = session.getDocumentIds() != null
                ? new ArrayList<>(session.getDocumentIds())
                : new ArrayList<>();

        if (documentIds.remove(documentId)) {
            session.setDocumentIds(documentIds);
            session.setUpdatedAt(Instant.now().toString());
            sessionRepository.save(session);
        }
    }
}