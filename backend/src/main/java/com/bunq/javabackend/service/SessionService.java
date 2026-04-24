package com.bunq.javabackend.service;

import com.bunq.javabackend.dto.request.CreateSessionRequestDTO;
import com.bunq.javabackend.dto.response.SessionResponseDTO;
import com.bunq.javabackend.exception.SessionNotFoundException;
import com.bunq.javabackend.helper.mapper.SessionMapper;
import com.bunq.javabackend.model.session.Session;
import com.bunq.javabackend.model.enums.SessionState;
import com.bunq.javabackend.repository.SessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class SessionService {

    private final SessionRepository sessionRepository;

    private static final Map<SessionState, Set<SessionState>> VALID_TRANSITIONS = Map.ofEntries(
            Map.entry(SessionState.CREATED, Set.of(SessionState.UPLOADING, SessionState.FAILED)),
            Map.entry(SessionState.UPLOADING, Set.of(SessionState.EXTRACTING, SessionState.FAILED)),
            Map.entry(SessionState.EXTRACTING, Set.of(SessionState.MAPPING, SessionState.FAILED)),
            Map.entry(SessionState.MAPPING, Set.of(SessionState.SCORING, SessionState.FAILED)),
            Map.entry(SessionState.SCORING, Set.of(SessionState.SANCTIONS, SessionState.FAILED)),
            Map.entry(SessionState.SANCTIONS, Set.of(SessionState.COMPLETE, SessionState.FAILED)),
            Map.entry(SessionState.COMPLETE, Set.of()),
            Map.entry(SessionState.FAILED, Set.of())
    );

    public SessionResponseDTO createSession(CreateSessionRequestDTO request) {
        Session session = SessionMapper.toModel(request);
        sessionRepository.save(session);
        return SessionMapper.toDto(session);
    }

    public List<SessionResponseDTO> listSessions() {
        return sessionRepository.scanAll().stream()
                .sorted(Comparator.comparing(Session::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(200)
                .map(SessionMapper::toDto)
                .toList();
    }

    public SessionResponseDTO getSession(String id) {
        return sessionRepository.findById(id)
                .map(SessionMapper::toDto)
                .orElseThrow(() -> new SessionNotFoundException(id));
    }

    public Session createSessionForJurisdiction(String launchId, String jurisdictionCode) {
        String now = Instant.now().toString();
        Session session = Session.builder()
                .id(java.util.UUID.randomUUID().toString())
                .state(SessionState.CREATED)
                .launchId(launchId)
                .jurisdictionCode(jurisdictionCode)
                .createdAt(now)
                .updatedAt(now)
                .build();
        sessionRepository.save(session);
        return session;
    }

    public void updateState(String sessionId, SessionState state) {
        Session session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new SessionNotFoundException(sessionId));

        SessionState currentState = session.getState();
        if (!isValidTransition(currentState, state)) {
            throw new IllegalStateException(
                    "Cannot transition session " + sessionId + " from " + currentState + " to " + state);
        }

        session.setState(state);
        session.setUpdatedAt(Instant.now().toString());
        sessionRepository.save(session);
    }

    private boolean isValidTransition(SessionState fromState, SessionState toState) {
        if (fromState == null) return true;
        Set<SessionState> validNextStates = VALID_TRANSITIONS.get(fromState);
        return validNextStates != null && validNextStates.contains(toState);
    }
}
