package com.bunq.javabackend.service.pipeline.stage;

import com.bunq.javabackend.dto.response.ExecutiveSummaryDTO;
import com.bunq.javabackend.model.enums.SanctionMatchStatus;
import com.bunq.javabackend.model.gap.Gap;
import com.bunq.javabackend.model.mapping.Mapping;
import com.bunq.javabackend.model.obligation.ObligationSource;
import com.bunq.javabackend.model.sanction.SanctionHit;
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
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Slf4j
@Service
public class NarrateStage implements Stage {

    private final BedrockService bedrockService;
    private final GapRepository gapRepository;
    private final MappingRepository mappingRepository;
    private final ObligationRepository obligationRepository;
    private final ControlRepository controlRepository;
    private final ObjectMapper objectMapper;
    private final ReportService reportService;
    private final SessionRepository sessionRepository;
    private final Executor pipelineExecutor;

    public NarrateStage(BedrockService bedrockService, GapRepository gapRepository,
                        MappingRepository mappingRepository, ObligationRepository obligationRepository,
                        ControlRepository controlRepository, ObjectMapper objectMapper,
                        ReportService reportService, SessionRepository sessionRepository,
                        @Qualifier("stageWorkerExecutor") Executor pipelineExecutor) {
        this.bedrockService = bedrockService;
        this.gapRepository = gapRepository;
        this.mappingRepository = mappingRepository;
        this.obligationRepository = obligationRepository;
        this.controlRepository = controlRepository;
        this.objectMapper = objectMapper;
        this.reportService = reportService;
        this.sessionRepository = sessionRepository;
        this.pipelineExecutor = pipelineExecutor;
    }

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

            List<SanctionHit> sanctionHits = ctx.getSanctionHits();
            String overallSeverity = determineOverall(gaps, sanctionHits);
            List<String> topRisks = extractTopRisks(gaps);
            String narrative = generateNarrative(gaps, mappings, overallSeverity, ctx.getSessionId());
            if (narrative == null || narrative.isBlank()) {
                log.warn("NarrateStage: narrative came back blank for session {}; executive summary will be marked unavailable", ctx.getSessionId());
            }

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
        }, pipelineExecutor);
    }

    private static final double AMBER_RESIDUAL_RISK_THRESHOLD = 0.4;
    private static final int AMBER_GAP_COUNT_THRESHOLD = 3;

    private String determineOverall(List<Gap> gaps, List<SanctionHit> sanctionHits) {
        boolean hasEscalation = gaps.stream()
                .anyMatch(g -> g.getEscalationRequired() != null && g.getEscalationRequired());
        if (hasEscalation) return "red";

        boolean hasRealSanctionHit = sanctionHits != null && sanctionHits.stream()
                .anyMatch(h -> h.getMatchStatus() == SanctionMatchStatus.flagged);
        if (hasRealSanctionHit) return "red";

        boolean hasUnscreened = sanctionHits != null && sanctionHits.stream()
                .anyMatch(h -> h.getMatchStatus() == SanctionMatchStatus.under_review);

        if (gaps.isEmpty() && !hasUnscreened) return "green";

        boolean hasHighRisk = gaps.stream()
                .anyMatch(g -> g.getResidualRisk() != null && g.getResidualRisk() >= AMBER_RESIDUAL_RISK_THRESHOLD);
        if (hasHighRisk || gaps.size() > AMBER_GAP_COUNT_THRESHOLD || hasUnscreened) return "amber";

        return "green";
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
                    .map(g -> {
                        HashMap<String, Object> entry = new HashMap<>();
                        entry.put("obligation_id", g.getObligationId() != null ? g.getObligationId() : "");
                        entry.put("narrative", g.getNarrative() != null ? g.getNarrative() : "");
                        entry.put("escalation", g.getEscalationRequired() != null && g.getEscalationRequired());
                        obligationRepository.findById(g.getObligationId() != null ? g.getObligationId() : "")
                                .ifPresent(obl -> {
                                    ObligationSource src = obl.getSource();
                                    if (src != null) {
                                        entry.put("regulation", src.getRegulation() != null ? src.getRegulation() : "");
                                        entry.put("article", src.getArticle() != null ? src.getArticle() : "");
                                        entry.put("section", src.getSection() != null ? src.getSection() : "");
                                        entry.put("source_text", src.getSourceText() != null ? src.getSourceText() : "");
                                    }
                                });
                        return entry;
                    }).toList());

            String requestJson = objectMapper.writeValueAsString(Map.of(
                    "anthropic_version", "bedrock-2023-05-31",
                    "max_tokens", 2048,
                    "system", SystemPrompts.NARRATE_EXEC_SUMMARY,
                    "messages", List.of(Map.of(
                            "role", "user",
                            "content", objectMapper.writeValueAsString(userInput)
                    ))
            ));

            JsonNode response = bedrockService.invokeModel(sessionId, "narrate",
                    BedrockModel.HAIKU.getModelId(), requestJson);
            String stopReason = response.path("stop_reason").asText(null);
            if ("max_tokens".equals(stopReason)) {
                log.warn("NarrateStage: narrative truncated at max_tokens for session {}", sessionId);
            }
            JsonNode content = response.path("content");
            if (content.isArray() && !content.isEmpty()) {
                String text = content.get(0).path("text").asText("");
                return text.isBlank() ? null : text;
            }
            log.warn("Narrative generation returned no content for session {}", sessionId);
            return null;
        } catch (Exception e) {
            log.warn("Narrative generation failed for session {}: {}", sessionId, e.getMessage());
            return null;
        }
    }
}
