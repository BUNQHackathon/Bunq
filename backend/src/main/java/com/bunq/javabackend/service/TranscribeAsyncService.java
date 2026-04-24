package com.bunq.javabackend.service;

import com.bunq.javabackend.service.pipeline.PipelineContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.transcribe.TranscribeClient;
import software.amazon.awssdk.services.transcribe.model.GetTranscriptionJobRequest;
import software.amazon.awssdk.services.transcribe.model.GetTranscriptionJobResponse;
import software.amazon.awssdk.services.transcribe.model.LanguageCode;
import software.amazon.awssdk.services.transcribe.model.Media;
import software.amazon.awssdk.services.transcribe.model.MediaFormat;
import software.amazon.awssdk.services.transcribe.model.StartTranscriptionJobRequest;
import software.amazon.awssdk.services.transcribe.model.TranscriptionJobStatus;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class TranscribeAsyncService {

    private final TranscribeClient transcribeClient;
    private final S3Client s3Client;
    private final ObjectMapper objectMapper;

    private static final Duration INITIAL_DELAY  = Duration.ofSeconds(5);
    private static final Duration MAX_DELAY      = Duration.ofSeconds(30);
    private static final Duration TOTAL_TIMEOUT  = Duration.ofMinutes(15);

    public String transcribeAudio(String bucket, String s3Key, PipelineContext ctx) {
        String jobName  = "ll-" + UUID.randomUUID();
        String outputKey = "transcribe-results/" + jobName + ".json";

        StartTranscriptionJobRequest.Builder reqBuilder = StartTranscriptionJobRequest.builder()
                .transcriptionJobName(jobName)
                .media(Media.builder()
                        .mediaFileUri("s3://" + bucket + "/" + s3Key)
                        .build())
                .outputBucketName(bucket)
                .outputKey(outputKey)
                .languageCode(LanguageCode.EN_US);

        inferMediaFormat(s3Key).ifPresent(reqBuilder::mediaFormat);

        transcribeClient.startTranscriptionJob(reqBuilder.build());
        log.info("Transcribe job started s3Key={} jobName={}", s3Key, jobName);

        Instant deadline = Instant.now().plus(TOTAL_TIMEOUT);
        Duration delay   = INITIAL_DELAY;

        while (Instant.now().isBefore(deadline)) {
            try { Thread.sleep(delay.toMillis()); }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }

            GetTranscriptionJobResponse resp = transcribeClient.getTranscriptionJob(
                    GetTranscriptionJobRequest.builder().transcriptionJobName(jobName).build());

            TranscriptionJobStatus status = resp.transcriptionJob().transcriptionJobStatus();
            log.debug("Transcribe poll jobName={} status={}", jobName, status);

            switch (status) {
                case COMPLETED -> {
                    return downloadTranscript(bucket, outputKey);
                }
                case FAILED -> throw new IllegalStateException(
                        "Transcribe job " + jobName + " failed: "
                        + resp.transcriptionJob().failureReason());
                case IN_PROGRESS, QUEUED -> {
                    ctx.getSseEmitterService().send(ctx.getSessionId(), "transcribe.polling",
                            Map.of("s3Key", s3Key, "jobName", jobName, "status", status.toString()));
                    delay = Duration.ofMillis(Math.min((long) (delay.toMillis() * 2), MAX_DELAY.toMillis()));
                }
                default -> throw new IllegalStateException("Unexpected Transcribe status: " + status);
            }
        }
        throw new IllegalStateException("Transcribe job " + jobName + " timed out after 15 minutes");
    }

    private String downloadTranscript(String bucket, String outputKey) {
        try (ResponseInputStream<GetObjectResponse> stream = s3Client.getObject(
                GetObjectRequest.builder().bucket(bucket).key(outputKey).build())) {
            JsonNode root = objectMapper.readTree(stream);
            JsonNode transcripts = root.path("results").path("transcripts");
            if (transcripts.isArray() && !transcripts.isEmpty()) {
                return transcripts.get(0).path("transcript").asText("");
            }
            return "";
        } catch (IOException e) {
            throw new RuntimeException("Failed to parse Transcribe result from s3://" + bucket + "/" + outputKey, e);
        }
    }

    private java.util.Optional<MediaFormat> inferMediaFormat(String s3Key) {
        if (s3Key == null) return java.util.Optional.empty();
        int dot = s3Key.lastIndexOf('.');
        if (dot < 0) return java.util.Optional.empty();
        String ext = s3Key.substring(dot + 1).toLowerCase();
        return switch (ext) {
            case "mp3"  -> java.util.Optional.of(MediaFormat.MP3);
            case "mp4"  -> java.util.Optional.of(MediaFormat.MP4);
            case "m4a"  -> java.util.Optional.of(MediaFormat.MP4);
            case "wav"  -> java.util.Optional.of(MediaFormat.WAV);
            case "flac" -> java.util.Optional.of(MediaFormat.FLAC);
            case "ogg"  -> java.util.Optional.of(MediaFormat.OGG);
            case "amr"  -> java.util.Optional.of(MediaFormat.AMR);
            case "webm" -> java.util.Optional.of(MediaFormat.WEBM);
            default     -> java.util.Optional.empty();
        };
    }
}
