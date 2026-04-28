package com.bunq.javabackend.service.documents;

import com.bunq.javabackend.dto.response.events.StageDeltaEvent;
import com.bunq.javabackend.service.pipeline.PipelineContext;
import com.bunq.javabackend.service.pipeline.PipelineStage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.textract.TextractClient;
import software.amazon.awssdk.services.textract.model.Block;
import software.amazon.awssdk.services.textract.model.BlockType;
import software.amazon.awssdk.services.textract.model.DocumentLocation;
import software.amazon.awssdk.services.textract.model.GetDocumentTextDetectionRequest;
import software.amazon.awssdk.services.textract.model.GetDocumentTextDetectionResponse;
import software.amazon.awssdk.services.textract.model.S3Object;
import software.amazon.awssdk.services.textract.model.StartDocumentTextDetectionRequest;
import software.amazon.awssdk.services.textract.model.StartDocumentTextDetectionResponse;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class TextractAsyncService {

    private final TextractClient textractClient;

    private static final Duration INITIAL_DELAY = Duration.ofSeconds(3);
    private static final Duration MAX_DELAY = Duration.ofSeconds(30);
    private static final Duration TOTAL_TIMEOUT = Duration.ofMinutes(10);

    public String extractText(String bucket, String s3Key, PipelineContext ctx) {
        StartDocumentTextDetectionResponse start = textractClient.startDocumentTextDetection(
            StartDocumentTextDetectionRequest.builder()
                .documentLocation(DocumentLocation.builder()
                    .s3Object(S3Object.builder().bucket(bucket).name(s3Key).build())
                    .build())
                .build());
        String jobId = start.jobId();
        log.info("Textract job started s3Key={} jobId={}", s3Key, jobId);

        Instant deadline = Instant.now().plus(TOTAL_TIMEOUT);
        Duration delay = INITIAL_DELAY;

        while (Instant.now().isBefore(deadline)) {
            try { Thread.sleep(delay.toMillis()); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }

            GetDocumentTextDetectionResponse resp = textractClient.getDocumentTextDetection(
                GetDocumentTextDetectionRequest.builder().jobId(jobId).build());

            switch (resp.jobStatus()) {
                case SUCCEEDED -> { return drainAllPages(jobId, resp); }
                case FAILED, PARTIAL_SUCCESS -> throw new IllegalStateException(
                    "Textract job " + jobId + " status=" + resp.jobStatus()
                    + " message=" + resp.statusMessage());
                case IN_PROGRESS -> {
                    ctx.getSseEmitterService().send(ctx.getSessionId(), StageDeltaEvent.builder()
                        .sessionId(ctx.getSessionId())
                        .timestamp(Instant.now())
                        .stage(PipelineStage.INGEST)
                        .itemType("ingest.polling")
                        .item(Map.of("s3Key", s3Key, "jobId", jobId))
                        .build());
                    delay = Duration.ofMillis(Math.min((long)(delay.toMillis() * 1.5), MAX_DELAY.toMillis()));
                }
                default -> throw new IllegalStateException("Unexpected status " + resp.jobStatus());
            }
        }
        throw new IllegalStateException("Textract job " + jobId + " timed out after " + TOTAL_TIMEOUT);
    }

    private String drainAllPages(String jobId, GetDocumentTextDetectionResponse firstPage) {
        StringBuilder sb = new StringBuilder();
        appendLines(sb, firstPage.blocks());

        String nextToken = firstPage.nextToken();
        while (nextToken != null) {
            GetDocumentTextDetectionResponse page = textractClient.getDocumentTextDetection(
                GetDocumentTextDetectionRequest.builder()
                    .jobId(jobId).nextToken(nextToken).build());
            appendLines(sb, page.blocks());
            nextToken = page.nextToken();
        }
        return sb.toString();
    }

    private void appendLines(StringBuilder sb, List<Block> blocks) {
        for (Block b : blocks) {
            if (b.blockType() == BlockType.LINE) {
                sb.append(b.text()).append('\n');
            }
        }
    }
}
