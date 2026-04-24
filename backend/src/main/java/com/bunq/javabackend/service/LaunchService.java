package com.bunq.javabackend.service;

import com.bunq.javabackend.dto.request.CreateLaunchRequestDTO;
import com.bunq.javabackend.dto.request.PipelineStartRequestDTO;
import com.bunq.javabackend.dto.response.LaunchResponseDTO;
import com.bunq.javabackend.exception.EntityAlreadyExistsException;
import com.bunq.javabackend.exception.NotFoundException;
import com.bunq.javabackend.model.document.Document;
import com.bunq.javabackend.model.enums.BedrockModel;
import com.bunq.javabackend.model.launch.JurisdictionRun;
import com.bunq.javabackend.model.launch.Launch;
import com.bunq.javabackend.model.session.Session;
import com.bunq.javabackend.repository.JurisdictionRunRepository;
import com.bunq.javabackend.repository.LaunchRepository;
import com.bunq.javabackend.repository.SessionRepository;
import com.bunq.javabackend.service.pipeline.PipelineOrchestrator;
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
    private final SessionService sessionService;
    private final SessionRepository sessionRepository;
    private final PipelineOrchestrator pipelineOrchestrator;
    private final AutoDocService autoDocService;
    private final BedrockService bedrockService;
    private final ObjectMapper objectMapper;

    public Launch createLaunch(CreateLaunchRequestDTO req) {
        String now = Instant.now().toString();
        List<String> counterparties = extractCounterparties(req.getBrief());
        Launch launch = Launch.builder()
                .id(UUID.randomUUID().toString())
                .name(req.getName())
                .brief(req.getBrief())
                .license(req.getLicense())
                .status("CREATED")
                .counterparties(counterparties)
                .createdAt(now)
                .updatedAt(now)
                .build();
        launchRepository.save(launch);
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

                    Output:""".formatted(brief);

            String requestJson = objectMapper.writeValueAsString(Map.of(
                    "anthropic_version", "bedrock-2023-05-31",
                    "max_tokens", 256,
                    "messages", List.of(Map.of(
                            "role", "user",
                            "content", prompt
                    ))
            ));

            JsonNode response = bedrockService.invokeModel(BedrockModel.HAIKU.getModelId(), requestJson);
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
                if (seen.size() == 10) break;
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
        return toDto(launch, runs);
    }

    public JurisdictionRun addJurisdiction(String launchId, String code) {
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
                .status("RUNNING")
                .verdict(null)
                .gapsCount(0)
                .sanctionsHits(0)
                .lastRunAt(now)
                .build();
        jurisdictionRunRepository.save(run);

        // Attach jurisdiction-filtered docs to session (up to 10), then fire pipeline async
        List<Document> docs = autoDocService.forJurisdiction(code);
        List<String> docIds = docs.stream().limit(10).map(Document::getId).toList();
        session.setDocumentIds(docIds);
        sessionRepository.save(session);

        PipelineStartRequestDTO req = PipelineStartRequestDTO.builder()
                .counterparties(List.of())
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
        run.setStatus("RUNNING");
        run.setLastRunAt(Instant.now().toString());
        jurisdictionRunRepository.save(run);

        // Attach jurisdiction-filtered docs to session (up to 10), then fire pipeline async
        List<Document> docs = autoDocService.forJurisdiction(code);
        List<String> docIds = docs.stream().limit(10).map(Document::getId).toList();
        session.setDocumentIds(docIds);
        sessionRepository.save(session);

        PipelineStartRequestDTO req = PipelineStartRequestDTO.builder()
                .counterparties(List.of())
                .build();
        pipelineOrchestrator.start(session.getId(), req);

        return run;
    }

    public com.bunq.javabackend.dto.response.LaunchSummaryDTO toSummaryWithCount(Launch launch) {
        int jurisdictionCount = jurisdictionRunRepository.findByLaunchId(launch.getId()).size();
        return toSummary(launch, jurisdictionCount);
    }
}
