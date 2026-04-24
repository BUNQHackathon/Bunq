package com.bunq.javabackend.service;

import com.bunq.javabackend.dto.response.SearchResponseDTO;
import com.bunq.javabackend.dto.response.SearchResponseDTO.Hit;
import com.bunq.javabackend.model.control.Control;
import com.bunq.javabackend.model.document.Document;
import com.bunq.javabackend.model.obligation.Obligation;
import com.bunq.javabackend.model.session.Session;
import com.bunq.javabackend.repository.ControlRepository;
import com.bunq.javabackend.repository.DocumentRepository;
import com.bunq.javabackend.repository.ObligationRepository;
import com.bunq.javabackend.repository.SessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Slf4j
@Service
@RequiredArgsConstructor
public class SearchService {

    // DynamoDB lacks native text search, so we scan and filter in memory.
    // Cap the scan to keep latency/cost bounded; refine with a real index when we have one.
    private static final int SCAN_CAP = 500;

    private final DocumentRepository documentRepository;
    private final SessionRepository sessionRepository;
    private final ObligationRepository obligationRepository;
    private final ControlRepository controlRepository;

    public SearchResponseDTO search(String rawQuery, int perTypeLimit) {
        String q = rawQuery == null ? "" : rawQuery.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) {
            return SearchResponseDTO.builder()
                    .query("")
                    .documents(List.of())
                    .sessions(List.of())
                    .obligations(List.of())
                    .controls(List.of())
                    .build();
        }

        int limit = Math.max(1, Math.min(perTypeLimit, 20));

        return SearchResponseDTO.builder()
                .query(rawQuery)
                .documents(searchDocuments(q, limit))
                .sessions(searchSessions(q, limit))
                .obligations(searchObligations(q, limit))
                .controls(searchControls(q, limit))
                .build();
    }

    private List<Hit> searchDocuments(String q, int limit) {
        List<Document> all = documentRepository.scanAll(SCAN_CAP);
        List<Hit> out = new ArrayList<>();
        for (Document d : all) {
            if (matches(q, d.getFilename(), d.getKind(), d.getContentType())) {
                out.add(Hit.builder()
                        .type("document")
                        .id(d.getId())
                        .title(d.getFilename() != null ? d.getFilename() : d.getId())
                        .subtitle(d.getKind())
                        .build());
                if (out.size() >= limit) break;
            }
        }
        return out;
    }

    private List<Hit> searchSessions(String q, int limit) {
        List<Session> all = sessionRepository.scanAll(SCAN_CAP);
        List<Hit> out = new ArrayList<>();
        for (Session s : all) {
            String state = s.getState() != null ? s.getState().name() : null;
            if (matches(q, s.getId(), s.getRegulation(), s.getPolicy(), s.getVerdict(), state)) {
                String title = s.getRegulation() != null && !s.getRegulation().isBlank()
                        ? s.getRegulation()
                        : "Session " + shortId(s.getId());
                String subtitle = state != null ? state.toLowerCase(Locale.ROOT) : null;
                out.add(Hit.builder()
                        .type("session")
                        .id(s.getId())
                        .title(title)
                        .subtitle(subtitle)
                        .build());
                if (out.size() >= limit) break;
            }
        }
        return out;
    }

    private List<Hit> searchObligations(String q, int limit) {
        List<Obligation> all = obligationRepository.scanAll(SCAN_CAP);
        List<Hit> out = new ArrayList<>();
        for (Obligation o : all) {
            if (matches(q, o.getSubject(), o.getAction(), o.getRiskCategory(), o.getRegulationId())) {
                String title = joinNonBlank(" — ", o.getSubject(), o.getAction());
                if (title.isBlank()) title = "Obligation " + shortId(o.getId());
                out.add(Hit.builder()
                        .type("obligation")
                        .id(o.getId())
                        .title(title)
                        .subtitle(o.getRiskCategory())
                        .build());
                if (out.size() >= limit) break;
            }
        }
        return out;
    }

    private List<Hit> searchControls(String q, int limit) {
        List<Control> all = controlRepository.scanAll(SCAN_CAP);
        List<Hit> out = new ArrayList<>();
        for (Control c : all) {
            String ctype = c.getControlType() != null ? c.getControlType().name() : null;
            if (matches(q, c.getDescription(), c.getOwner(), ctype, c.getEvidenceType())) {
                String title = c.getDescription() != null && !c.getDescription().isBlank()
                        ? c.getDescription()
                        : "Control " + shortId(c.getId());
                out.add(Hit.builder()
                        .type("control")
                        .id(c.getId())
                        .title(title)
                        .subtitle(c.getOwner())
                        .build());
                if (out.size() >= limit) break;
            }
        }
        return out;
    }

    private static boolean matches(String q, String... fields) {
        for (String f : fields) {
            if (f != null && f.toLowerCase(Locale.ROOT).contains(q)) return true;
        }
        return false;
    }

    private static String joinNonBlank(String sep, String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p == null || p.isBlank()) continue;
            if (sb.length() > 0) sb.append(sep);
            sb.append(p);
        }
        return sb.toString();
    }

    private static String shortId(String id) {
        if (id == null) return "";
        return id.length() > 8 ? id.substring(0, 8) : id;
    }
}
