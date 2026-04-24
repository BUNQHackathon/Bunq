package com.bunq.javabackend.client;

import com.bunq.javabackend.dto.response.CounterpartyDTO;
import com.bunq.javabackend.dto.response.sidecar.GraphDAG;
import com.bunq.javabackend.exception.NotFoundException;
import com.bunq.javabackend.exception.SidecarCommunicationException;
import com.bunq.javabackend.model.sanction.Counterparty;
import com.bunq.javabackend.model.sanction.SanctionHit;
import com.bunq.javabackend.model.sanction.SanctionMatch;
import com.bunq.javabackend.model.enums.SanctionMatchStatus;
import com.bunq.javabackend.util.IdGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// Expected sidecar response for POST /sanctions/screen:
// {"results": [{"counterparty": {...}, "match_status": "clear|flagged|review", "hits": [...]}]}
// Expected sidecar response for GET /proof-tree/{mappingId}:
// {"nodes": [...], "edges": [...]}
// Expected sidecar response for GET /compliance-map/{sessionId}:
// {"nodes": [...], "edges": [...]}

@Slf4j
@Component
public class SidecarClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public SidecarClient(
            WebClient.Builder webClientBuilder,
            @Value("${sidecar.base-url}") String baseUrl,
            @Value("${sidecar.token:}") String token,
            ObjectMapper objectMapper) {
        WebClient.Builder builder = webClientBuilder.baseUrl(baseUrl);
        if (!token.isBlank()) {
            builder.defaultHeader("X-Sidecar-Token", token);
        }
        this.webClient = builder.build();
        this.objectMapper = objectMapper;
    }

    public List<SanctionHit> screenSanctions(String sessionId, List<Counterparty> counterparties, String briefText) {
        Map<String, Object> body = Map.of(
                "session_id", sessionId,
                "counterparties", counterparties.stream().map(cp -> Map.of(
                        "name", cp.getName() != null ? cp.getName() : "",
                        "country", cp.getCountry() != null ? cp.getCountry() : "",
                        "type", cp.getType() != null ? cp.getType().name() : ""
                )).toList(),
                "brief_text", briefText != null ? briefText : ""
        );

        try {
            JsonNode response = webClient.post()
                    .uri("/sanctions/screen")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(TIMEOUT)
                    .block();

            return parseSanctionHits(response, sessionId);
        } catch (WebClientResponseException e) {
            throw new SidecarCommunicationException("Sidecar sanctions screen failed: " + e.getStatusCode(), e);
        } catch (Exception e) {
            throw new SidecarCommunicationException("Sidecar sanctions screen failed: " + e.getMessage(), e);
        }
    }

    public GraphDAG getProofTree(String mappingId) {
        try {
            return webClient.get()
                    .uri("/proof-tree/{mappingId}", mappingId)
                    .retrieve()
                    .bodyToMono(GraphDAG.class)
                    .timeout(TIMEOUT)
                    .block();
        } catch (WebClientResponseException.NotFound e) {
            throw new NotFoundException("Proof tree not found: " + mappingId);
        } catch (WebClientResponseException e) {
            throw new SidecarCommunicationException("Sidecar proof-tree failed: " + e.getStatusCode(), e);
        } catch (Exception e) {
            throw new SidecarCommunicationException("Sidecar proof-tree failed: " + e.getMessage(), e);
        }
    }

    public GraphDAG getComplianceMap(String sessionId) {
        try {
            return webClient.get()
                    .uri("/compliance-map/{sessionId}", sessionId)
                    .retrieve()
                    .bodyToMono(GraphDAG.class)
                    .timeout(TIMEOUT)
                    .block();
        } catch (WebClientResponseException.NotFound e) {
            throw new NotFoundException("Compliance map not found: " + sessionId);
        } catch (WebClientResponseException e) {
            throw new SidecarCommunicationException("Sidecar compliance-map failed: " + e.getStatusCode(), e);
        } catch (Exception e) {
            throw new SidecarCommunicationException("Sidecar compliance-map failed: " + e.getMessage(), e);
        }
    }

    public void health() {
        webClient.get()
                .uri("/health")
                .retrieve()
                .toBodilessEntity()
                .timeout(Duration.ofSeconds(3))
                .block();
    }

    private List<SanctionHit> parseSanctionHits(JsonNode response, String sessionId) {
        List<SanctionHit> hits = new ArrayList<>();
        if (response == null) return hits;

        JsonNode results = response.path("results");
        if (!results.isArray()) return hits;

        for (JsonNode item : results) {
            SanctionHit hit = new SanctionHit();
            hit.setId(IdGenerator.generateSanctionsHitId());
            hit.setSessionId(sessionId);
            hit.setScreenedAt(Instant.now());

            String statusStr = item.path("match_status").asText("clear");
            try {
                hit.setMatchStatus(SanctionMatchStatus.valueOf(statusStr.toLowerCase()));
            } catch (Exception ignored) {
                hit.setMatchStatus(SanctionMatchStatus.clear);
            }

            JsonNode cpNode = item.path("counterparty");
            if (!cpNode.isMissingNode()) {
                Counterparty cp = new Counterparty();
                cp.setName(cpNode.path("name").asText(null));
                cp.setCountry(cpNode.path("country").asText(null));
                hit.setCounterparty(cp);
            }

            JsonNode hitsNode = item.path("hits");
            if (hitsNode.isArray()) {
                List<SanctionMatch> matchList = new ArrayList<>();
                for (JsonNode h : hitsNode) {
                    SanctionMatch match = new SanctionMatch();
                    match.setListSource(h.path("list_source").asText(null));
                    match.setEntityName(h.path("entity_name").asText(null));
                    match.setMatchScore(h.path("match_score").asDouble(0.0));
                    matchList.add(match);
                }
                hit.setHits(matchList);
            }

            hits.add(hit);
        }
        return hits;
    }
}
