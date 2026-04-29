package com.bunq.javabackend.service.pipeline.stage;

import com.bunq.javabackend.dto.response.ExecutiveSummaryDTO;
import com.bunq.javabackend.model.gap.Gap;
import com.bunq.javabackend.model.mapping.Mapping;
import com.bunq.javabackend.model.enums.BedrockModel;
import com.bunq.javabackend.repository.GapRepository;
import com.bunq.javabackend.repository.MappingRepository;
import com.bunq.javabackend.repository.ObligationRepository;
import com.bunq.javabackend.repository.ControlRepository;
import com.bunq.javabackend.repository.SessionRepository;
import com.bunq.javabackend.service.ai.bedrock.BedrockService;
import com.bunq.javabackend.service.launch.ReportService;
import com.bunq.javabackend.service.pipeline.PipelineContext;
import com.bunq.javabackend.service.pipeline.PipelineStage;
import com.bunq.javabackend.service.pipeline.Stage;
import com.bunq.javabackend.service.pipeline.prompts.SystemPrompts;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
@RequiredArgsConstructor
public class NarrateStage implements Stage {

    private final BedrockService bedrockService;
    private final GapRepository gapRepository;
    private final MappingRepository mappingRepository;
    private final ObligationRepository obligationRepository;
    private final ControlRepository controlRepository;
    private final ObjectMapper objectMapper;
    private final ReportService reportService;
    private final SessionRepository sessionRepository;

    @Override
    public PipelineStage stage() {
        return PipelineStage.NARRATE;
    }

    @Override
    public CompletableFuture<Void> execute(PipelineContext ctx) {
        return CompletableFuture.runAsync(() -> {
            List<Gap> gaps = ctx.getGaps();
            if (gaps.isEmpty()) {
                gaps = gapRepository.findBySessionId(ctx.getSessionId());
            }
            List<Mapping> mappings = ctx.getMappings();
            if (mappings.isEmpty()) {
                mappings = mappingRepository.findBySessionId(ctx.getSessionId());
            }

            int obligationCount = ctx.getObligations().isEmpty()
                    ? obligationRepository.findBySessionId(ctx.getSessionId()).size()
                    : ctx.getObligations().size();

            int controlCount = ctx.getControls().isEmpty()
                    ? controlRepository.findBySessionId(ctx.getSessionId()).size()
                    : ctx.getControls().size();

            String overallSeverity = determineOverall(gaps);
            List<String> topRisks = extractTopRisks(gaps);
            String narrative = generateNarrative(gaps, mappings, overallSeverity, ctx.getSessionId());

            sessionRepository.findById(ctx.getSessionId()).ifPresent(session -> {
                session.setExecutiveSummary(narrative);
                sessionRepository.save(session);
            });

            ExecutiveSummaryDTO summary = ExecutiveSummaryDTO.builder()
                    .overall(overallSeverity)
                    .gapCount(gaps.size())
                    .obligationCount(obligationCount)
                    .controlCount(controlCount)
                    .topRisks(topRisks)
                    .narrative(narrative)
                    .build();

            ctx.setSummary(summary);

            try {
                String reportUrl = reportService.generate(ctx, summary);
                ctx.setReportUrl(reportUrl);
            } catch (Exception e) {
                log.warn("Report generation failed for session {}: {}", ctx.getSessionId(), e.getMessage());
                // intentionally non-fatal — pipeline should still complete
            }

            ctx.getSseEmitterService().send(ctx.getSessionId(), "narrative.completed", summary);

            log.info("NarrateStage: summary generated for session {} overall={}", ctx.getSessionId(), overallSeverity);
        });
    }

    private String determineOverall(List<Gap> gaps) {
        if (gaps.isEmpty()) return "green";
        long critical = gaps.stream()
                .filter(g -> g.getEscalationRequired() != null && g.getEscalationRequired())
                .count();
        if (critical > 0) return "red";
        if (gaps.size() > 3) return "amber";
        return "amber";
    }

    private List<String> extractTopRisks(List<Gap> gaps) {
        return gaps.stream()
                .limit(5)
                .filter(g -> g.getNarrative() != null)
                .map(g -> g.getNarrative().length() > 100 ? g.getNarrative().substring(0, 100) : g.getNarrative())
                .toList();
    }

    private String generateNarrative(List<Gap> gaps, List<Mapping> mappings, String overallSeverity,
                                      String sessionId) {
        try {
            HashMap<String, Object> userInput = new HashMap<String, Object>();
            userInput.put("overall_severity", overallSeverity);
            userInput.put("gap_count", gaps.size());
            userInput.put("mapping_count", mappings.size());
            userInput.put("top_gaps", gaps.stream().limit(3)
                    .map(g -> Map.of(
                            "obligation_id", g.getObligationId() != null ? g.getObligationId() : "",
                            "narrative", g.getNarrative() != null ? g.getNarrative() : "",
                            "escalation", g.getEscalationRequired() != null && g.getEscalationRequired()
                    )).toList());

            String requestJson = objectMapper.writeValueAsString(Map.of(
                    "anthropic_version", "bedrock-2023-05-31",
                    "max_tokens", 512,
                    "system", SystemPrompts.NARRATE_EXEC_SUMMARY,
                    "messages", List.of(Map.of(
                            "role", "user",
                            "content", objectMapper.writeValueAsString(userInput)
                    ))
            ));

            JsonNode response = bedrockService.invokeModel(sessionId, "narrate",
                    BedrockModel.HAIKU.getModelId(), requestJson);
            JsonNode content = response.path("content");
            if (content.isArray() && !content.isEmpty()) {
                return content.get(0).path("text").asText("");
            }
            return "Compliance analysis complete. See gaps and mappings for details.";
        } catch (Exception e) {
            log.warn("Narrative generation failed: {}", e.getMessage());
            return "Compliance analysis complete. " + gaps.size() + " gap(s) identified.";
        }
    }
}
