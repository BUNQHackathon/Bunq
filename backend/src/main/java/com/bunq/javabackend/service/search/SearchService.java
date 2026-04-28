package com.bunq.javabackend.service.search;

import com.bunq.javabackend.dto.response.SearchResponseDTO;
import com.bunq.javabackend.dto.response.SearchResponseDTO.Hit;
import com.bunq.javabackend.controller.launch.JurisdictionsOverviewController.Jurisdiction;
import com.bunq.javabackend.model.control.Control;
import com.bunq.javabackend.model.document.Document;
import com.bunq.javabackend.model.enums.RunStatus;
import com.bunq.javabackend.model.launch.Launch;
import com.bunq.javabackend.model.obligation.Obligation;
import com.bunq.javabackend.model.session.Session;
import com.bunq.javabackend.repository.ControlRepository;
import com.bunq.javabackend.repository.DocumentRepository;
import com.bunq.javabackend.repository.LaunchRepository;
import com.bunq.javabackend.repository.ObligationRepository;
import com.bunq.javabackend.repository.SessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
    @Value("${search.scan-cap}")
    private int scanCap;

    @Value("${search.per-type-limit-max}")
    private int perTypeLimitMax;

    private final DocumentRepository documentRepository;
    private final SessionRepository sessionRepository;
    private final ObligationRepository obligationRepository;
    private final ControlRepository controlRepository;
    private final LaunchRepository launchRepository;

    public SearchResponseDTO search(String rawQuery, int perTypeLimit) {
        String q = rawQuery == null ? "" : rawQuery.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) {
            return SearchResponseDTO.builder()
                    .query("")
                    .documents(List.of())
                    .sessions(List.of())
                    .obligations(List.of())
                    .controls(List.of())
                    .launches(List.of())
                    .jurisdictions(List.of())
                    .build();
        }

        int limit = Math.max(1, Math.min(perTypeLimit, perTypeLimitMax));

        return SearchResponseDTO.builder()
                .query(rawQuery)
                .documents(searchDocuments(q, limit))
                .sessions(searchSessions(q, limit))
                .obligations(searchObligations(q, limit))
                .controls(searchControls(q, limit))
                .launches(searchLaunches(q, limit))
                .jurisdictions(searchJurisdictions(q, limit))
                .build();
    }

    private List<Hit> searchDocuments(String q, int limit) {
        List<Document> all = documentRepository.scanAll(scanCap);
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
        List<Session> all = sessionRepository.scanAll(scanCap);
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
        List<Obligation> all = obligationRepository.scanAll(scanCap);
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
        List<Control> all = controlRepository.scanAll(scanCap);
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

    private List<Hit> searchLaunches(String q, int limit) {
        List<Launch> all = launchRepository.findAll();
        List<Hit> out = new ArrayList<>();
        for (Launch l : all) {
            if (matches(q, l.getName(), l.getBrief(), l.getLicense(), l.getStatus() != null ? l.getStatus().name() : null)) {
                out.add(Hit.builder()
                        .type("launch")
                        .id(l.getId())
                        .title(l.getName() != null ? l.getName() : l.getId())
                        .subtitle(l.getBrief() != null ? l.getBrief() : l.getLicense())
                        .build());
                if (out.size() >= limit) break;
            }
        }
        return out;
    }

    private static final List<com.bunq.javabackend.controller.launch.JurisdictionsOverviewController.Jurisdiction> JURISDICTION_CATALOG =
            com.bunq.javabackend.controller.launch.JurisdictionsOverviewController.CATALOG;

    private List<Hit> searchJurisdictions(String q, int limit) {
        List<Hit> out = new ArrayList<>();
        for (com.bunq.javabackend.controller.launch.JurisdictionsOverviewController.Jurisdiction j : JURISDICTION_CATALOG) {
            if (matches(q, j.code(), j.name(), j.license(), j.regulator(), j.status())) {
                out.add(Hit.builder()
                        .type("jurisdiction")
                        .id(j.code())
                        .title(j.name())
                        .subtitle(j.regulator() != null ? j.regulator() : j.license())
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
