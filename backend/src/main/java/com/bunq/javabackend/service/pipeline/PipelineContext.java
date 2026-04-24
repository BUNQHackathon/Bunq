package com.bunq.javabackend.service.pipeline;

import com.bunq.javabackend.model.control.Control;
import com.bunq.javabackend.model.sanction.Counterparty;
import com.bunq.javabackend.model.gap.Gap;
import com.bunq.javabackend.model.mapping.Mapping;
import com.bunq.javabackend.model.obligation.Obligation;
import com.bunq.javabackend.model.sanction.SanctionHit;
import com.bunq.javabackend.service.sse.SseEmitterService;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class PipelineContext {

    private final String sessionId;
    private String regulation;
    private String policy;
    private final List<Counterparty> counterparties;
    private String briefText;
    private final SseEmitterService sseEmitterService;
    private String jurisdictionCode;

    public PipelineContext(String sessionId, String regulation, String policy,
                           List<Counterparty> counterparties, String briefText,
                           SseEmitterService sseEmitterService) {
        this.sessionId = sessionId;
        this.regulation = regulation;
        this.policy = policy;
        this.counterparties = counterparties;
        this.briefText = briefText;
        this.sseEmitterService = sseEmitterService;
    }

    private List<IngestChunk> ingestedChunks = new ArrayList<>();
    /** Per-document ingested text produced by IngestStage; consumed by Phase 4 extract stages. */
    private List<IngestedDocument> ingestedDocuments = new ArrayList<>();
    private List<Obligation> obligations = new ArrayList<>();
    private List<Control> controls = new ArrayList<>();
    private List<Mapping> mappings = new ArrayList<>();
    private List<Gap> gaps = new ArrayList<>();
    private List<SanctionHit> sanctionHits = new ArrayList<>();
    private com.bunq.javabackend.dto.response.ExecutiveSummaryDTO summary;
    private String reportUrl;
}
