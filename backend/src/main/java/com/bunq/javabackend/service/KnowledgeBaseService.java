package com.bunq.javabackend.service;

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
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseService {

    private final BedrockAgentRuntimeAsyncClient bedrockAgentRuntimeAsyncClient;
    private final KnowledgeBaseConfig knowledgeBaseConfig;

    public record RetrievedChunk(
            KbType kbType,
            String chunkId,
            double score,
            String s3Uri,
            String text
    ) {}

    public CompletableFuture<List<RetrievedChunk>> retrieveControls(String query, int topK) {
        return retrieve(knowledgeBaseConfig.getControlsId(), query, topK, KbType.CONTROLS);
    }

    public CompletableFuture<List<RetrievedChunk>> retrieveAll(String query, int perKbTopK, int topNMerged) {
        CompletableFuture<List<RetrievedChunk>> regFuture = retrieve(knowledgeBaseConfig.getRegulationsId(), query, perKbTopK, KbType.REGULATIONS);
        CompletableFuture<List<RetrievedChunk>> polFuture = retrieve(knowledgeBaseConfig.getPoliciesId(), query, perKbTopK, KbType.POLICIES);
        CompletableFuture<List<RetrievedChunk>> conFuture = retrieve(knowledgeBaseConfig.getControlsId(), query, perKbTopK, KbType.CONTROLS);

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

        if (jurisdictionFilter == null || jurisdictionFilter.isEmpty()) {
            return retrieveAll(query, perKbTopK, topNMerged);
        }

        // Build an OR filter: doc.jurisdictions must contain at least one of the requested codes.
        List<RetrievalFilter> clauses = jurisdictionFilter.stream()
                .map(code -> RetrievalFilter.builder()
                        .listContains(FilterAttribute.builder()
                                .key("jurisdictions")
                                .value(Document.fromString(code))
                                .build())
                        .build())
                .toList();

        RetrievalFilter filter = clauses.size() == 1
                ? clauses.get(0)
                : RetrievalFilter.builder().orAll(clauses).build();

        CompletableFuture<List<RetrievedChunk>> regFuture =
                retrieveWithFilter(knowledgeBaseConfig.getRegulationsId(), query, perKbTopK, KbType.REGULATIONS, filter);
        CompletableFuture<List<RetrievedChunk>> polFuture =
                retrieveWithFilter(knowledgeBaseConfig.getPoliciesId(), query, perKbTopK, KbType.POLICIES, filter);
        CompletableFuture<List<RetrievedChunk>> conFuture =
                retrieveWithFilter(knowledgeBaseConfig.getControlsId(), query, perKbTopK, KbType.CONTROLS, filter);

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

    private CompletableFuture<List<RetrievedChunk>> retrieveWithFilter(
            String kbId, String query, int topK, KbType kbType, RetrievalFilter filter) {
        RetrieveRequest request = RetrieveRequest.builder()
                .knowledgeBaseId(kbId)
                .retrievalQuery(q -> q.text(query))
                .retrievalConfiguration(r -> r.vectorSearchConfiguration(v -> v
                        .numberOfResults(topK)
                        .filter(filter)))
                .build();
        return bedrockAgentRuntimeAsyncClient.retrieve(request)
                .thenApply(response -> mapResults(response, kbType))
                .exceptionally(ex -> {
                    log.warn("KB filtered retrieval failed for {} kb {}: {}", kbType, kbId, ex.getMessage());
                    return List.of();
                });
    }

    private CompletableFuture<List<RetrievedChunk>> retrieve(String kbId, String query, int topK, KbType kbType) {
        RetrieveRequest request = RetrieveRequest.builder()
                .knowledgeBaseId(kbId)
                .retrievalQuery(q -> q.text(query))
                .retrievalConfiguration(r -> r.vectorSearchConfiguration(v -> v.numberOfResults(topK)))
                .build();

        return bedrockAgentRuntimeAsyncClient.retrieve(request)
                .thenApply(response -> mapResults(response, kbType))
                .exceptionally(ex -> {
                    log.warn("KB retrieval failed for {} kb {}: {}", kbType, kbId, ex.getMessage());
                    return List.of();
                });
    }

    private List<RetrievedChunk> mapResults(RetrieveResponse response, KbType kbType) {
        return response.retrievalResults().stream()
                .map(r -> toChunk(r, kbType))
                .filter(Objects::nonNull)
                .toList();
    }

    private RetrievedChunk toChunk(KnowledgeBaseRetrievalResult result, KbType kbType) {
        try {
            String s3Uri = null;
            if (result.location() != null && result.location().s3Location() != null) {
                s3Uri = result.location().s3Location().uri();
            }
            String chunkId = s3Uri != null
                    ? s3Uri + "#" + Integer.toHexString(Objects.hashCode(result.content().text()))
                    : kbType.name() + "#" + Integer.toHexString(Objects.hashCode(result.content().text()));
            double score = result.score() != null ? result.score() : 0.0;
            return new RetrievedChunk(kbType, chunkId, score, s3Uri, result.content().text());
        } catch (Exception ex) {
            log.warn("Failed to map KB result for {}: {}", kbType, ex.getMessage());
            return null;
        }
    }
}
