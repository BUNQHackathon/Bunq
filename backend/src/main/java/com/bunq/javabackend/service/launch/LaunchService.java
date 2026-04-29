package com.bunq.javabackend.service.launch;

import com.bunq.javabackend.dto.request.CreateLaunchRequestDTO;
import com.bunq.javabackend.dto.request.PipelineStartRequestDTO;
import com.bunq.javabackend.dto.response.sidecar.GraphDAG;
import com.bunq.javabackend.dto.response.JurisdictionRunResponseDTO;
import com.bunq.javabackend.dto.response.LaunchResponseDTO;
import com.bunq.javabackend.dto.response.LaunchSummaryDTO;
import com.bunq.javabackend.dto.response.LaunchSummaryDTO.JurisdictionSummary;
import com.bunq.javabackend.exception.EntityAlreadyExistsException;
import com.bunq.javabackend.exception.NotFoundException;
import com.bunq.javabackend.model.document.Document;
import com.bunq.javabackend.model.enums.BedrockModel;
import com.bunq.javabackend.model.gap.Gap;
import com.bunq.javabackend.model.gap.RecommendedAction;
import com.bunq.javabackend.model.launch.JurisdictionRun;
import com.bunq.javabackend.model.launch.Launch;
import com.bunq.javabackend.model.enums.RunStatus;
import com.bunq.javabackend.model.launch.LaunchKind;
import com.bunq.javabackend.model.session.Session;
import com.bunq.javabackend.repository.ControlRepository;
import com.bunq.javabackend.repository.GapRepository;
import com.bunq.javabackend.repository.JurisdictionRunRepository;
import com.bunq.javabackend.repository.LaunchRepository;
import com.bunq.javabackend.repository.ObligationRepository;
import com.bunq.javabackend.repository.SessionRepository;
import com.bunq.javabackend.service.AutoDocService;
import com.bunq.javabackend.service.ai.bedrock.BedrockService;
import com.bunq.javabackend.service.compliance.EvidenceService;
import com.bunq.javabackend.service.pipeline.PipelineOrchestrator;
import com.bunq.javabackend.service.session.SessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.bunq.javabackend.helper.mapper.LaunchMapper.toDto;
import static com.bunq.javabackend.helper.mapper.LaunchMapper.toSummary;

@Slf4j
@Service
@RequiredArgsConstructor
public class LaunchService {

    private final LaunchRepository launchRepository;
    private final JurisdictionRunRepository jurisdictionRunRepository;
    private final GapRepository gapRepository;
    private final ObligationRepository obligationRepository;
    private final ControlRepository controlRepository;
    private final SessionService sessionService;
    private final SessionRepository sessionRepository;
    private final PipelineOrchestrator pipelineOrchestrator;
    private final AutoDocService autoDocService;
    private final BedrockService bedrockService;
    private final ObjectMapper objectMapper;
    private final EvidenceService evidenceService;

    public Launch createLaunch(CreateLaunchRequestDTO req) {
        String now = Instant.now().toString();
        List<String> counterparties = extractCounterparties(req.getBrief());
        Launch launch = Launch.builder()
                .id(UUID.randomUUID().toString())
                .name(req.getName())
                .brief(req.getBrief())
                .license(req.getLicense())
                .kind(req.getKind() != null ? req.getKind() : LaunchKind.PRODUCT)
                .status(RunStatus.CREATED)
                .counterparties(counterparties)
                .createdAt(now)
                .updatedAt(now)
                .build();
        launchRepository.save(launch);
        if (req.getJurisdictions() != null) {
            for (String code : req.getJurisdictions()) {
                try {
                    provisionJurisdiction(launch.getId(), code);
                } catch (Exception e) {
                    log.warn("Failed to add jurisdiction {} for launch {}: {}", code, launch.getId(), e.getMessage());
                }
            }
        }

        return launch;
    }

    private List<String> extractCounterparties(String brief) {
        if (brief == null || brief.isBlank()) {
            return List.of();
        }
        try {
            String prompt = """
                    You are an entity extractor for a banking compliance tool. From the feature brief below, extract a JSON array of distinct external counterparties: companies, vendors, payment processors, banks, custodians, partners, jurisdictions/countries. Do NOT extract regulation names (MiCA, GDPR), product features, internal product names, generic words ("crypto", "card"), or amounts. Return ONLY a valid JSON array of strings. No prose, no markdown, no code fences.

                    Brief:
                    \"""
                    %s
                    \"""

                    Output:"""
                    .formatted(brief);

            String requestJson = objectMapper.writeValueAsString(Map.of(
                    "anthropic_version", "bedrock-2023-05-31",
                    "max_tokens", 256,
                    "messages", List.of(Map.of(
                            "role", "user",
                            "content", prompt))));

            JsonNode response = bedrockService.invokeModel(null, null,
                    BedrockModel.HAIKU.getModelId(), requestJson);
            String text = response.path("content").get(0).path("text").asText("").strip();

            // Strip markdown code fences if Claude adds them
            if (text.startsWith("```")) {
                text = text.replaceAll("(?s)^```[a-z]*\\n?", "").replaceAll("```$", "").strip();
            }

            JsonNode arr = objectMapper.readTree(text);
            if (!arr.isArray()) {
                return List.of();
            }

            // Dedupe case-insensitively, preserve first-seen casing, cap at 10
            var seen = new LinkedHashMap<String, String>();
            for (JsonNode node : arr) {
                String val = node.asText().strip();
                if (!val.isEmpty()) {
                    seen.putIfAbsent(val.toLowerCase(), val);
                }
                if (seen.size() == 10)
                    break;
            }
            return new ArrayList<>(seen.values());
        } catch (Exception e) {
            log.warn("Counterparty extraction failed, proceeding with empty list: {}", e.getMessage());
            return List.of();
        }
    }

    public List<Launch> listLaunches() {
        return launchRepository.findAll().stream()
                .sorted(Comparator.comparing(Launch::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    public LaunchResponseDTO getLaunch(String id) {
        Launch launch = launchRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Launch not found: " + id));
        List<JurisdictionRun> runs = jurisdictionRunRepository.findByLaunchId(id);
        List<JurisdictionRunResponseDTO> runDtos = mapRunsWithSummary(runs);

        return toDto(launch, runDtos);
    }

    private List<JurisdictionRunResponseDTO> mapRunsWithSummary(List<JurisdictionRun> runs) {
        List<JurisdictionRunResponseDTO> result = new ArrayList<>();
        for (JurisdictionRun run : runs) {
            boolean proofPackAvailable = run.getProofPackS3Key() != null;

            Session session = run.getCurrentSessionId() != null
                    ? sessionRepository.findById(run.getCurrentSessionId()).orElse(null)
                    : null;

            Integer regulationsCovered = session != null
                    ? (session.getDocumentIds() == null ? 0 : session.getDocumentIds().size())
                    : 0;

            Integer obligationsCount = run.getCurrentSessionId() != null
                    ? obligationRepository.findBySessionId(run.getCurrentSessionId()).size()
                    : 0;
            Integer controlsCount = run.getCurrentSessionId() != null
                    ? controlRepository.findBySessionId(run.getCurrentSessionId()).size()
                    : 0;

            String verdict;
            String summary;
            List<String> requiredChanges;
            List<String> blockers;

            if (regulationsCovered == 0) {
                verdict = "UNKNOWN";
                summary = "No regulations found for this jurisdiction";
                requiredChanges = List.of();
                blockers = List.of();
            } else {
                verdict = run.getVerdict();
                String persistedSummary = session != null && session.getExecutiveSummary() != null
                        && !session.getExecutiveSummary().isBlank() ? session.getExecutiveSummary() : null;
                if ("GREEN".equals(verdict)) {
                    summary = persistedSummary != null ? persistedSummary : "Can ship as-is";
                    requiredChanges = List.of();
                    blockers = List.of();
                } else if ("AMBER".equals(verdict)) {
                    summary = persistedSummary != null ? persistedSummary : "Requires changes";
                    List<Gap> gaps = run.getCurrentSessionId() != null
                            ? gapRepository.findBySessionId(run.getCurrentSessionId())
                            : List.of();
                    requiredChanges = gaps.stream()
                            .flatMap(g -> g.getRecommendedActions() == null ? java.util.stream.Stream.empty()
                                    : g.getRecommendedActions().stream())
                            .map(RecommendedAction::getAction)
                            .filter(a -> a != null && !a.isBlank())
                            .map(a -> trimToHeadline(a))
                            .distinct()
                            .limit(10)
                            .toList();
                    blockers = List.of();
                } else if ("RED".equals(verdict)) {
                    summary = persistedSummary != null ? persistedSummary : "Blocked";
                    List<Gap> gaps = run.getCurrentSessionId() != null
                            ? gapRepository.findBySessionId(run.getCurrentSessionId())
                            : List.of();
                    blockers = gaps.stream()
                            .filter(g -> g.getResidualRisk() != null)
                            .sorted(Comparator.comparingDouble(Gap::getResidualRisk).reversed())
                            .limit(3)
                            .map(Gap::getNarrative)
                            .filter(n -> n != null)
                            .map(n -> trimToHeadline(n))
                            .toList();
                    requiredChanges = List.of();
                } else {
                    summary = "Analysis in progress";
                    requiredChanges = List.of();
                    blockers = List.of();
                }
            }

            result.add(toDto(run, verdict, summary, requiredChanges, blockers, proofPackAvailable,
                    regulationsCovered, obligationsCount, controlsCount));
        }
        return result;
    }

    private String trimToHeadline(String text) {
        if (text == null) return null;
        int dot = text.indexOf(". ");
        String firstSentence = dot >= 0 ? text.substring(0, dot + 1) : text;
        if (firstSentence.length() <= 140) return firstSentence;
        return text.substring(0, 140) + "…";
    }

    private String displayedVerdict(JurisdictionRun run) {
        Integer regulationsCovered = run.getCurrentSessionId() != null
                ? sessionRepository.findById(run.getCurrentSessionId())
                        .map(s -> s.getDocumentIds() == null ? 0 : s.getDocumentIds().size())
                        .orElse(0)
                : 0;
        if (regulationsCovered == 0)
            return "UNKNOWN";
        return run.getVerdict();
    }

    private JurisdictionRun provisionJurisdiction(String launchId, String code) {
        launchRepository.findById(launchId)
                .orElseThrow(() -> new NotFoundException("Launch not found: " + launchId));
        jurisdictionRunRepository.findByLaunchIdAndCode(launchId, code).ifPresent(existing -> {
            throw new EntityAlreadyExistsException(
                    "Jurisdiction " + code + " already exists for launch " + launchId);
        });

        Session session = sessionService.createSessionForJurisdiction(launchId, code);

        String now = Instant.now().toString();
        JurisdictionRun run = JurisdictionRun.builder()
                .launchId(launchId)
                .jurisdictionCode(code)
                .currentSessionId(session.getId())
                .status(RunStatus.RUNNING)
                .verdict(null)
                .gapsCount(0)
                .sanctionsHits(0)
                .lastRunAt(now)
                .build();
        jurisdictionRunRepository.save(run);

        // Attach jurisdiction-filtered docs to session (up to 10), then fire pipeline
        // async
        List<Document> docs = autoDocService.forJurisdiction(code);
        List<String> docIds = docs.stream().map(Document::getId).toList();
        session.setDocumentIds(docIds);
        sessionRepository.save(session);

        PipelineStartRequestDTO req = PipelineStartRequestDTO.builder()
                .counterparties(List.of())
                .launchId(launchId)
                .jurisdictionCode(code)
                .build();
        pipelineOrchestrator.start(session.getId(), req);

        return run;
    }

    public JurisdictionRun runJurisdiction(String launchId, String code) {
        JurisdictionRun run = jurisdictionRunRepository.findByLaunchIdAndCode(launchId, code)
                .orElseThrow(() -> new NotFoundException(
                        "JurisdictionRun not found: launch=" + launchId + " code=" + code));

        Session session = sessionService.createSessionForJurisdiction(launchId, code);

        run.setCurrentSessionId(session.getId());
        run.setStatus(RunStatus.RUNNING);
        run.setLastRunAt(Instant.now().toString());
        jurisdictionRunRepository.save(run);

        // Attach jurisdiction-filtered docs to session (up to 10), then fire pipeline
        // async
        List<Document> docs = autoDocService.forJurisdiction(code);
        List<String> docIds = docs.stream().map(Document::getId).toList();
        session.setDocumentIds(docIds);
        sessionRepository.save(session);

        String regulationText = docs.stream()
                .filter(d -> "regulation".equals(d.getKind()))
                .map(Document::getExtractedText)
                .filter(t -> t != null && !t.isBlank())
                .findFirst()
                .map(t -> t.substring(0, Math.min(500, t.length())))
                .orElse(null);
        String policyText = docs.stream()
                .filter(d -> "policy".equals(d.getKind()))
                .map(Document::getExtractedText)
                .filter(t -> t != null && !t.isBlank())
                .findFirst()
                .map(t -> t.substring(0, Math.min(500, t.length())))
                .orElse(null);

        PipelineStartRequestDTO req = PipelineStartRequestDTO.builder()
                .counterparties(List.of())
                .launchId(launchId)
                .jurisdictionCode(code)
                .regulation(regulationText)
                .policy(policyText)
                .build();
        pipelineOrchestrator.start(session.getId(), req);

        return run;
    }

    public List<JurisdictionRun> rerunFailed(String launchId) {
        launchRepository.findById(launchId)
                .orElseThrow(() -> new NotFoundException("Launch not found: " + launchId));
        List<JurisdictionRun> all = jurisdictionRunRepository.findByLaunchId(launchId);
        List<JurisdictionRun> failed = all.stream()
                .filter(r -> r.getStatus() == RunStatus.FAILED)
                .toList();

        return failed.stream()
                .map(r -> runJurisdiction(launchId, r.getJurisdictionCode()))
                .toList();
    }

    public GraphDAG getComplianceMap(String launchId, String code) {
        var runOpt = jurisdictionRunRepository.findByLaunchIdAndCode(launchId, code);
        if (runOpt.isEmpty()) {
            return GraphDAG.builder().nodes(List.of()).edges(List.of()).build();
        }
        String sessionId = runOpt.get().getCurrentSessionId();
        if (sessionId == null || sessionId.isBlank()) {
            return GraphDAG.builder().nodes(List.of()).edges(List.of()).build();
        }

        return evidenceService.getComplianceMap(sessionId);
    }

    public void deleteLaunch(String id) {
        Launch launch = launchRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Launch not found: " + id));
        List<JurisdictionRun> runs = jurisdictionRunRepository.findByLaunchId(id);
        for (JurisdictionRun run : runs) {
            jurisdictionRunRepository.delete(run);
        }
        List<Session> sessions = sessionRepository.findByLaunchId(id);
        for (Session session : sessions) {
            sessionRepository.deleteById(session.getId());
        }
        launchRepository.deleteById(launch.getId());
        log.info("Deleted launch {} (runs={}, sessions={})", id, runs.size(), sessions.size());
    }

    public LaunchSummaryDTO toSummaryWithCount(Launch launch) {
        List<JurisdictionRun> runs = jurisdictionRunRepository.findByLaunchId(launch.getId());
        String aggregateVerdict = computeAggregateVerdict(runs);
        List<JurisdictionSummary> jurisdictions = runs.stream()
                .map(r -> new JurisdictionSummary(r.getJurisdictionCode(), displayedVerdict(r), r.getStatus() != null ? r.getStatus().name() : null))
                .toList();

        return toSummary(launch, runs.size(), aggregateVerdict, jurisdictions);
    }

    private String computeAggregateVerdict(List<JurisdictionRun> runs) {
        if (runs.isEmpty())
            return null;
        boolean hasRed = false;
        boolean hasAmber = false;
        boolean hasGreen = false;
        boolean allUnknown = true;
        for (JurisdictionRun run : runs) {
            String v = displayedVerdict(run);
            if ("RED".equals(v)) {
                hasRed = true;
                allUnknown = false;
            } else if ("AMBER".equals(v)) {
                hasAmber = true;
                allUnknown = false;
            } else if ("GREEN".equals(v)) {
                hasGreen = true;
                allUnknown = false;
            } else if (!"UNKNOWN".equals(v) && v != null) {
                allUnknown = false;
            }
        }
        if (hasRed)
            return "RED";
        if (hasAmber)
            return "AMBER";
        if (hasGreen)
            return "GREEN";
        if (allUnknown)
            return "UNKNOWN";
        return null;
    }
}
