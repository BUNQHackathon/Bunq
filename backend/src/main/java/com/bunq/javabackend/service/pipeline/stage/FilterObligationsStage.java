package com.bunq.javabackend.service.pipeline.stage;

import com.bunq.javabackend.model.enums.BedrockModel;
import com.bunq.javabackend.model.obligation.Obligation;
import com.bunq.javabackend.repository.ObligationRepository;
import com.bunq.javabackend.service.ai.bedrock.BedrockService;
import com.bunq.javabackend.service.ai.bedrock.ToolDefinitions;
import com.bunq.javabackend.service.pipeline.PipelineContext;
import com.bunq.javabackend.service.pipeline.PipelineStage;
import com.bunq.javabackend.service.pipeline.Stage;
import com.bunq.javabackend.service.pipeline.prompts.SystemPrompts;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Slf4j
@Service
public class FilterObligationsStage implements Stage {

    static final double RELEVANCE_THRESHOLD = 0.3;

    private final BedrockService bedrockService;
    private final ObligationRepository obligationRepository;
    private final Executor pipelineExecutor;

    public FilterObligationsStage(BedrockService bedrockService,
                                  ObligationRepository obligationRepository,
                                  @Qualifier("stageWorkerExecutor") Executor pipelineExecutor) {
        this.bedrockService = bedrockService;
        this.obligationRepository = obligationRepository;
        this.pipelineExecutor = pipelineExecutor;
    }

    @Override
    public PipelineStage stage() {
        return PipelineStage.FILTER_OBLIGATIONS;
    }

    @Override
    public CompletableFuture<Void> execute(PipelineContext ctx) {
        String brief = ctx.getBriefText();
        if (brief == null || brief.isBlank()) {
            log.info("FilterObligationsStage: no brief for session {}; skipping", ctx.getSessionId());
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.runAsync(() -> {
            List<Obligation> obligations = ctx.getObligations();
            int total = obligations.size();

            log.info("FilterObligationsStage: scoring {} obligations for session {}", total, ctx.getSessionId());

            List<CompletableFuture<Void>> futures = new ArrayList<>(total);
            for (Obligation obl : obligations) {
                futures.add(CompletableFuture.runAsync(
                        () -> scoreObligation(obl, brief, ctx.getSessionId()),
                        pipelineExecutor));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            // Filter: keep obligations with no score (null) or score >= threshold
            List<Obligation> relevant = obligations.stream()
                    .filter(o -> o.getRelevanceScore() == null || o.getRelevanceScore() >= RELEVANCE_THRESHOLD)
                    .toList();

            int dropped = total - relevant.size();

            // Replace ctx obligations in-place (synchronized list)
            obligations.clear();
            obligations.addAll(relevant);

            log.info("FilterObligationsStage: session {} — total={} relevant={} dropped={}",
                    ctx.getSessionId(), total, relevant.size(), dropped);

            if (dropped > 0) {
                ctx.getSseEmitterService().send(ctx.getSessionId(), "obligations.filtered",
                        Map.of("total", total, "relevant", relevant.size(), "dropped", dropped));
            }
        });
    }

    private void scoreObligation(Obligation obl, String brief, String sessionId) {
        try {
            Map<String, Object> userInput = Map.of(
                    "brief", brief,
                    "subject", obl.getSubject() != null ? obl.getSubject() : "",
                    "action", obl.getAction() != null ? obl.getAction() : "",
                    "risk_category", obl.getRiskCategory() != null ? obl.getRiskCategory() : "",
                    "deontic", obl.getDeontic() != null ? obl.getDeontic().name() : ""
            );

            JsonNode result = bedrockService.invokeModelWithTool(
                    sessionId, "filter_obligations",
                    BedrockModel.HAIKU.getModelId(),
                    SystemPrompts.RELEVANCE_SCORER,
                    userInput,
                    ToolDefinitions.SCORE_RELEVANCE_TOOL
            );

            if (result != null && !result.isMissingNode()) {
                double score = result.path("relevance_score").asDouble(1.0);
                String reason = result.path("relevance_reason").asText(null);
                obl.setRelevanceScore(score);
                obl.setRelevanceReason(reason);
            } else {
                obl.setRelevanceScore(1.0);
            }
        } catch (Exception e) {
            log.warn("FilterObligationsStage: scoring failed for obligation {} in session {}; defaulting to 1.0 — {}",
                    obl.getId(), sessionId, e.getMessage());
            obl.setRelevanceScore(1.0);
        }
        obligationRepository.save(obl);
    }
}
