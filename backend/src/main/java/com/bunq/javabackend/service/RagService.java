package com.bunq.javabackend.service;

import com.bunq.javabackend.dto.request.RagRequest;
import com.bunq.javabackend.dto.response.Citation;
import com.bunq.javabackend.dto.response.RagResponse;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import software.amazon.awssdk.services.bedrockagentruntime.BedrockAgentRuntimeAsyncClient;
import software.amazon.awssdk.services.bedrockagentruntime.BedrockAgentRuntimeClient;
import software.amazon.awssdk.services.bedrockagentruntime.model.RetrieveAndGenerateResponse;
import software.amazon.awssdk.services.bedrockagentruntime.model.RetrieveAndGenerateStreamRequest;
import software.amazon.awssdk.services.bedrockagentruntime.model.RetrieveAndGenerateStreamResponseHandler;
import software.amazon.awssdk.services.bedrockagentruntime.model.RetrieveAndGenerateType;
import software.amazon.awssdk.services.bedrockagentruntime.model.RetrievedReference;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class RagService {

    private final BedrockAgentRuntimeClient client;
    private final BedrockAgentRuntimeAsyncClient asyncClient;
    private final String kbId;
    private final String accountId;
    private final String region;
    private final String modelId;
    private String modelArn;

    public RagService(
            BedrockAgentRuntimeClient client,
            BedrockAgentRuntimeAsyncClient asyncClient,
            @Value("${aws.bedrock.kb.regulations-id}") String kbId,
            @Value("${aws.bedrock.region}") String region,
            @Value("${aws.account-id}") String accountId,
            @Value("${aws.bedrock.claude-model-id}") String modelId
    ) {
        this.client = client;
        this.asyncClient = asyncClient;
        this.kbId = kbId;
        this.region = region;
        this.accountId = accountId;
        this.modelId = modelId;
    }

    @PostConstruct
    void init() {
        this.modelArn = "arn:aws:bedrock:" + region + ":" + accountId + ":inference-profile/" + modelId;
    }

    public RagResponse query(RagRequest req) {
        RetrieveAndGenerateResponse resp = client.retrieveAndGenerate(r -> r
                .input(i -> i.text(req.query()))
                .retrieveAndGenerateConfiguration(c -> c
                        .type(RetrieveAndGenerateType.KNOWLEDGE_BASE)
                        .knowledgeBaseConfiguration(kb -> kb
                                .knowledgeBaseId(kbId)
                                .modelArn(modelArn)
                                .retrievalConfiguration(rc -> rc
                                        .vectorSearchConfiguration(vs -> vs
                                                .numberOfResults(8)
                                        )
                                )
                        )
                )
        );

        List<Citation> citations = new ArrayList<>();
        for (software.amazon.awssdk.services.bedrockagentruntime.model.Citation cit : resp.citations()) {
            for (RetrievedReference ref : cit.retrievedReferences()) {
                String text = ref.content() != null ? ref.content().text() : null;
                String source = (ref.location() != null && ref.location().s3Location() != null)
                        ? ref.location().s3Location().uri() : null;
                if (text != null || source != null) {
                    citations.add(new Citation(text, source));
                }
            }
        }
        return new RagResponse(resp.output().text(), citations);
    }

    public SseEmitter queryStream(RagRequest req) {
        SseEmitter emitter = new SseEmitter(300_000L);

        RetrieveAndGenerateStreamRequest request = RetrieveAndGenerateStreamRequest.builder()
                .input(i -> i.text(req.query()))
                .retrieveAndGenerateConfiguration(c -> c
                        .type(RetrieveAndGenerateType.KNOWLEDGE_BASE)
                        .knowledgeBaseConfiguration(kb -> kb
                                .knowledgeBaseId(kbId)
                                .modelArn(modelArn)
                                .retrievalConfiguration(rc -> rc
                                        .vectorSearchConfiguration(vs -> vs
                                                .numberOfResults(8)
                                        )
                                )
                        )
                )
                .build();

        RetrieveAndGenerateStreamResponseHandler.Visitor visitor = RetrieveAndGenerateStreamResponseHandler.Visitor.builder()
                .onOutput(output -> {
                    try {
                        emitter.send(SseEmitter.event()
                                .name("chunk")
                                .data(Map.of("delta", output.text())));
                    } catch (Exception e) {
                        emitter.completeWithError(e);
                    }
                })
                .onCitation(citEvent -> {
                    try {
                        List<Citation> citations = new ArrayList<>();
                        for (RetrievedReference ref : citEvent.retrievedReferences()) {
                            String text = ref.content() != null ? ref.content().text() : null;
                            String source = (ref.location() != null && ref.location().s3Location() != null)
                                    ? ref.location().s3Location().uri() : null;
                            if (text != null || source != null) {
                                citations.add(new Citation(text, source));
                            }
                        }
                        emitter.send(SseEmitter.event()
                                .name("citations")
                                .data(Map.of("citations", citations)));
                    } catch (Exception e) {
                        emitter.completeWithError(e);
                    }
                })
                .build();

        RetrieveAndGenerateStreamResponseHandler handler = RetrieveAndGenerateStreamResponseHandler.builder()
                .subscriber(visitor)
                .onError(err -> {
                    try {
                        emitter.send(SseEmitter.event()
                                .name("error")
                                .data(Map.of("message", err.getMessage())));
                    } catch (Exception ignored) {
                    }
                    emitter.completeWithError(err);
                })
                .onComplete(() -> {
                    try {
                        emitter.send(SseEmitter.event().name("done").data(""));
                    } catch (Exception ignored) {
                    }
                    emitter.complete();
                })
                .build();

        asyncClient.retrieveAndGenerateStream(request, handler);

        return emitter;
    }
}
