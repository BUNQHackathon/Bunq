package com.bunq.javabackend.service.ai.kb;

import com.bunq.javabackend.config.KnowledgeBaseConfig;
import com.bunq.javabackend.model.enums.KbType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.document.Document;
import software.amazon.awssdk.services.bedrockagentruntime.BedrockAgentRuntimeAsyncClient;
import software.amazon.awssdk.services.bedrockagentruntime.model.FilterAttribute;
import software.amazon.awssdk.services.bedrockagentruntime.model.KnowledgeBaseRetrievalResult;
import software.amazon.awssdk.services.bedrockagentruntime.model.RetrievalFilter;
import software.amazon.awssdk.services.bedrockagentruntime.model.RetrieveRequest;
import software.amazon.awssdk.services.bedrockagentruntime.model.RetrieveResponse;

import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseService {

    private final BedrockAgentRuntimeAsyncClient bedrockAgentRuntimeAsyncClient;
    private final KnowledgeBaseConfig knowledgeBaseConfig;

    public record RetrievedChunk(
            KbType kbType,
            String knowledgeBaseId,
            String knowledgeBaseLabel,
            String chunkId,
            double score,
            String s3Uri,
            String text,
            Map<String, String> metadata
    ) {}

    public CompletableFuture<List<RetrievedChunk>> retrieveControls(String query, int topK) {
        KnowledgeBaseConfig.Entry entry = knowledgeBaseConfig.findByKbType(KbType.CONTROLS)
                .orElseGet(() -> entry(knowledgeBaseConfig.getControlsId(), "Internal controls", KbType.CONTROLS));
        return retrieve(entry.getKnowledgeBaseId(), query, topK, entry.getKbType(), entry.getLabel());
    }

    public CompletableFuture<List<RetrievedChunk>> retrieveAll(String query, int perKbTopK, int topNMerged) {
        KnowledgeBaseConfig.Entry regulations = knowledgeBaseConfig.findByKbType(KbType.REGULATIONS)
                .orElseGet(() -> entry(knowledgeBaseConfig.getRegulationsId(), "Regulations", KbType.REGULATIONS));
        KnowledgeBaseConfig.Entry policies = knowledgeBaseConfig.findByKbType(KbType.POLICIES)
                .orElseGet(() -> entry(knowledgeBaseConfig.getPoliciesId(), "bunq policies", KbType.POLICIES));
        KnowledgeBaseConfig.Entry controls = knowledgeBaseConfig.findByKbType(KbType.CONTROLS)
                .orElseGet(() -> entry(knowledgeBaseConfig.getControlsId(), "Internal controls", KbType.CONTROLS));

        CompletableFuture<List<RetrievedChunk>> regFuture = retrieve(regulations.getKnowledgeBaseId(), query, perKbTopK, regulations.getKbType(), regulations.getLabel());
        CompletableFuture<List<RetrievedChunk>> polFuture = retrieve(policies.getKnowledgeBaseId(), query, perKbTopK, policies.getKbType(), policies.getLabel());
        CompletableFuture<List<RetrievedChunk>> conFuture = retrieve(controls.getKnowledgeBaseId(), query, perKbTopK, controls.getKbType(), controls.getLabel());

        return CompletableFuture.allOf(regFuture, polFuture, conFuture)
                .thenApply(v -> {
                    List<List<RetrievedChunk>> results = Arrays.asList(
                            regFuture.join(),
                            polFuture.join(),
                            conFuture.join()
                    );
                    return results.stream()
                            .flatMap(List::stream)
                            .sorted(Comparator.comparingDouble(RetrievedChunk::score).reversed())
                            .limit(topNMerged)
                            .toList();
                });
    }

    /**
     * Like {@link #retrieveAll(String, int, int)} but restricts chunks to those whose
     * {@code jurisdictions} metadata attribute contains any value in {@code jurisdictionFilter}.
     *
     * <p>Uses {@link RetrievalFilter#orAll(List)} with one {@code listContains} clause per
     * filter value — matches chunks where the stored list intersects the requested set.</p>
     *
     * <p>If {@code jurisdictionFilter} is null or empty, delegates to the unfiltered
     * {@link #retrieveAll(String, int, int)} without touching the existing code path.</p>
     *
     * <p>Import path for future stage wiring:
     * {@code software.amazon.awssdk.services.bedrockagentruntime.model.RetrievalFilter}
     * {@code software.amazon.awssdk.services.bedrockagentruntime.model.FilterAttribute}
     * {@code software.amazon.awssdk.core.document.Document} (for Document.fromString / Document.fromList)</p>
     */
    public CompletableFuture<List<RetrievedChunk>> retrieveAllWithFilter(
            String query, int perKbTopK, int topNMerged, List<String> jurisdictionFilter) {

        RetrievalFilter filter = buildJurisdictionFilter(jurisdictionFilter);
        if (filter == null) {
            return retrieveAll(query, perKbTopK, topNMerged);
        }

        KnowledgeBaseConfig.Entry regulations = knowledgeBaseConfig.findByKbType(KbType.REGULATIONS)
                .orElseGet(() -> entry(knowledgeBaseConfig.getRegulationsId(), "Regulations", KbType.REGULATIONS));
        KnowledgeBaseConfig.Entry policies = knowledgeBaseConfig.findByKbType(KbType.POLICIES)
                .orElseGet(() -> entry(knowledgeBaseConfig.getPoliciesId(), "bunq policies", KbType.POLICIES));
        KnowledgeBaseConfig.Entry controls = knowledgeBaseConfig.findByKbType(KbType.CONTROLS)
                .orElseGet(() -> entry(knowledgeBaseConfig.getControlsId(), "Internal controls", KbType.CONTROLS));

        CompletableFuture<List<RetrievedChunk>> regFuture =
                retrieveWithFilter(regulations.getKnowledgeBaseId(), query, perKbTopK, regulations.getKbType(), regulations.getLabel(), filter);
        CompletableFuture<List<RetrievedChunk>> polFuture =
                retrieveWithFilter(policies.getKnowledgeBaseId(), query, perKbTopK, policies.getKbType(), policies.getLabel(), filter);
        CompletableFuture<List<RetrievedChunk>> conFuture =
                retrieveWithFilter(controls.getKnowledgeBaseId(), query, perKbTopK, controls.getKbType(), controls.getLabel(), filter);

        return CompletableFuture.allOf(regFuture, polFuture, conFuture)
                .thenApply(v -> {
                    List<List<RetrievedChunk>> results = Arrays.asList(
                            regFuture.join(),
                            polFuture.join(),
                            conFuture.join()
                    );
                    return results.stream()
                            .flatMap(List::stream)
                            .sorted(Comparator.comparingDouble(RetrievedChunk::score).reversed())
                            .limit(topNMerged)
                            .toList();
                });
    }

    public CompletableFuture<List<RetrievedChunk>> retrieveKnowledgeBaseWithFilter(
            KnowledgeBaseConfig.Entry entry, String query, int topK, List<String> jurisdictionFilter) {

        RetrievalFilter filter = buildJurisdictionFilter(jurisdictionFilter);
        if (filter == null) {
            return retrieve(entry.getKnowledgeBaseId(), query, topK, entry.getKbType(), entry.getLabel());
        }
        return retrieveWithFilter(entry.getKnowledgeBaseId(), query, topK, entry.getKbType(), entry.getLabel(), filter);
    }

    private CompletableFuture<List<RetrievedChunk>> retrieveWithFilter(
            String kbId, String query, int topK, KbType kbType, String kbLabel, RetrievalFilter filter) {
        RetrieveRequest request = RetrieveRequest.builder()
                .knowledgeBaseId(kbId)
                .retrievalQuery(q -> q.text(query))
                .retrievalConfiguration(r -> r.vectorSearchConfiguration(v -> v
                        .numberOfResults(topK)
                        .filter(filter)))
                .build();

        return bedrockAgentRuntimeAsyncClient.retrieve(request)
                .thenApply(response -> mapResults(response, kbType, kbId, kbLabel))
                .exceptionally(ex -> {
                    log.warn("KB filtered retrieval failed for {} kb {}: {}", kbType, kbId, ex.getMessage());
                    return List.of();
                });
    }

    private CompletableFuture<List<RetrievedChunk>> retrieve(String kbId, String query, int topK, KbType kbType, String kbLabel) {
        RetrieveRequest request = RetrieveRequest.builder()
                .knowledgeBaseId(kbId)
                .retrievalQuery(q -> q.text(query))
                .retrievalConfiguration(r -> r.vectorSearchConfiguration(v -> v.numberOfResults(topK)))
                .build();

        return bedrockAgentRuntimeAsyncClient.retrieve(request)
                .thenApply(response -> mapResults(response, kbType, kbId, kbLabel))
                .exceptionally(ex -> {
                    log.warn("KB retrieval failed for {} kb {}: {}", kbType, kbId, ex.getMessage());
                    return List.of();
                });
    }

    private RetrievalFilter buildJurisdictionFilter(List<String> jurisdictionFilter) {
        if (jurisdictionFilter == null || jurisdictionFilter.isEmpty()) {
            return null;
        }

        // Build an OR filter: doc.jurisdictions must contain at least one of the requested codes.
        List<RetrievalFilter> clauses = jurisdictionFilter.stream()
                .filter(Objects::nonNull)
                .filter(code -> !code.isBlank())
                .map(code -> RetrievalFilter.builder()
                        .listContains(FilterAttribute.builder()
                                .key("jurisdictions")
                                .value(Document.fromString(code))
                                .build())
                        .build())
                .toList();

        if (clauses.isEmpty()) {
            return null;
        }
        return clauses.size() == 1
                ? clauses.get(0)
                : RetrievalFilter.builder().orAll(clauses).build();
    }

    private List<RetrievedChunk> mapResults(RetrieveResponse response, KbType kbType, String kbId, String kbLabel) {
        return response.retrievalResults().stream()
                .map(r -> toChunk(r, kbType, kbId, kbLabel))
                .filter(Objects::nonNull)
                .toList();
    }

    private RetrievedChunk toChunk(KnowledgeBaseRetrievalResult result, KbType kbType, String kbId, String kbLabel) {
        try {
            String s3Uri = null;
            if (result.location() != null && result.location().s3Location() != null) {
                s3Uri = result.location().s3Location().uri();
            }
            String chunkId = s3Uri != null
                    ? s3Uri + "#" + Integer.toHexString(Objects.hashCode(result.content().text()))
                    : kbType.name() + "#" + Integer.toHexString(Objects.hashCode(result.content().text()));
            double score = result.score() != null ? result.score() : 0.0;

            // Extract metadata map — Bedrock returns Map<String, Document>; convert to Map<String, String>
            Map<String, String> metadata = new HashMap<>();
            if (result.metadata() != null) {
                result.metadata().forEach((k, v) -> {
                    if (v != null) metadata.put(k, documentValueToString(v));
                });
            }

            return new RetrievedChunk(kbType, kbId, kbLabel, chunkId, score, s3Uri, result.content().text(), metadata);
        } catch (Exception ex) {
            log.warn("Failed to map KB result for {}: {}", kbType, ex.getMessage());
            return null;
        }
    }

    private static String documentValueToString(Document value) {
        if (value == null || value.isNull()) {
            return "";
        }
        if (value.isString()) {
            return value.asString();
        }
        if (value.isNumber()) {
            return value.asNumber().stringValue();
        }
        if (value.isBoolean()) {
            return Boolean.toString(value.asBoolean());
        }
        if (value.isList()) {
            return value.asList().stream()
                    .map(KnowledgeBaseService::documentValueToString)
                    .collect(Collectors.joining(","));
        }
        return value.toString();
    }

    private static KnowledgeBaseConfig.Entry entry(String kbId, String label, KbType kbType) {
        KnowledgeBaseConfig.Entry entry = new KnowledgeBaseConfig.Entry();
        entry.setKnowledgeBaseId(kbId);
        entry.setLabel(label);
        entry.setKbType(kbType);
        entry.setKey(kbType.name().toLowerCase());
        return entry;
    }
}
