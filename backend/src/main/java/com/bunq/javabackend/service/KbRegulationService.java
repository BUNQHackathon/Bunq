package com.bunq.javabackend.service;

import com.bunq.javabackend.dto.response.kb.KbRegulationDetailDTO;
import com.bunq.javabackend.dto.response.kb.KbRegulationSummaryDTO;
import com.bunq.javabackend.dto.response.kb.KbSectionDTO;
import com.bunq.javabackend.exception.NotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.bedrockagentruntime.BedrockAgentRuntimeClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.bedrockagentruntime.model.RetrieveResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class KbRegulationService {

    private static final String BUCKET = "launchlens-kb-regulations";

    private final S3Client s3;
    private final S3Presigner presigner;
    private final BedrockAgentRuntimeClient agentRuntimeClient;
    private final String kbId;

    public KbRegulationService(
            S3Client s3,
            S3Presigner presigner,
            BedrockAgentRuntimeClient agentRuntimeClient,
            @Value("${aws.bedrock.kb.regulations-id}") String kbId
    ) {
        this.s3 = s3;
        this.presigner = presigner;
        this.agentRuntimeClient = agentRuntimeClient;
        this.kbId = kbId;
    }

    public List<KbRegulationSummaryDTO> list() {
        ListObjectsV2Request req = ListObjectsV2Request.builder().bucket(BUCKET).build();
        ListObjectsV2Response resp = s3.listObjectsV2(req);
        return resp.contents().stream()
            .map(obj -> {
                String key = obj.key();
                String id = slugify(stripExt(key));
                String title = humanize(stripExt(key));
                String ext = extensionOf(key);
                String cat = guessCategory(key);
                String updated = obj.lastModified() != null ? obj.lastModified().toString() : "";
                return new KbRegulationSummaryDTO(id, key, title, cat, "EU", ext, obj.size(), updated);
            })
            .toList();
    }

    public KbRegulationDetailDTO get(String id) {
        ListObjectsV2Request listReq = ListObjectsV2Request.builder().bucket(BUCKET).build();
        List<S3Object> all = s3.listObjectsV2(listReq).contents();
        S3Object matched = all.stream()
            .filter(o -> slugify(stripExt(o.key())).equals(id))
            .findFirst()
            .orElseThrow(() -> new NotFoundException("Document not found: " + id));

        String key = matched.key();
        String title = humanize(stripExt(key));
        String cat = guessCategory(key);
        String updated = matched.lastModified() != null ? matched.lastModified().toString() : "";
        String downloadUrl = presignGetUrl(key);
        List<KbSectionDTO> sections = retrieveDocSections(title, key);

        return new KbRegulationDetailDTO(id, title, cat, "EU", updated, downloadUrl, sections);
    }

    private String presignGetUrl(String key) {
        GetObjectRequest req = GetObjectRequest.builder().bucket(BUCKET).key(key).build();
        GetObjectPresignRequest presignReq = GetObjectPresignRequest.builder()
            .signatureDuration(Duration.ofMinutes(15))
            .getObjectRequest(req)
            .build();
        return presigner.presignGetObject(presignReq).url().toString();
    }

    private List<KbSectionDTO> retrieveDocSections(String title, String key) {
        try {
            RetrieveResponse resp = agentRuntimeClient.retrieve(r -> r
                .knowledgeBaseId(kbId)
                .retrievalQuery(q -> q.text(title))
                .retrievalConfiguration(rc -> rc
                    .vectorSearchConfiguration(vs -> vs.numberOfResults(8))
                )
            );

            ArrayList<KbSectionDTO> sections = new ArrayList<KbSectionDTO>();
            int idx = 1;
            for (var result : resp.retrievalResults()) {
                String txt = result.content() != null ? result.content().text() : null;
                if (txt == null || txt.isBlank()) continue;
                String loc = result.location() != null && result.location().s3Location() != null
                    ? result.location().s3Location().uri() : "";
                if (!loc.contains(key)) continue;
                sections.add(new KbSectionDTO("Excerpt " + idx++, txt));
                if (sections.size() >= 6) break;
            }
            if (sections.isEmpty()) {
                sections.add(new KbSectionDTO("Preview", "This document is available for download — full content requires opening the source file."));
            }
            return sections;
        } catch (Exception e) {
            return List.of(new KbSectionDTO("Preview", "Source: " + key));
        }
    }

    private static String stripExt(String s) {
        int dot = s.lastIndexOf('.');
        return dot == -1 ? s : s.substring(0, dot);
    }

    private static String extensionOf(String s) {
        int dot = s.lastIndexOf('.');
        return dot == -1 ? "" : s.substring(dot + 1).toLowerCase();
    }

    private static String slugify(String s) {
        return s.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
    }

    private static String humanize(String s) {
        if (s.toLowerCase().contains("celex") && s.contains("32023R1114")) {
            return "MiCA — Markets in Crypto-Assets Regulation";
        }
        if (s.toLowerCase().startsWith("gdpr")) {
            return "GDPR Dataset";
        }
        return s.replace('_', ' ').replace('-', ' ');
    }

    private static String guessCategory(String key) {
        String lower = key.toLowerCase();
        if (lower.contains("gdpr") || lower.contains("privacy") || lower.contains("cookie")) return "Privacy";
        if (lower.contains("aml") || lower.contains("kyc") || lower.contains("sanction")) return "AML";
        if (lower.contains("license") || lower.contains("dnb") || lower.contains("mica") || lower.contains("celex")) return "Licensing";
        if (lower.contains("terms") || lower.contains("t&c") || lower.contains("gtc")) return "Terms & Conditions";
        if (lower.contains("report") || lower.contains("annual")) return "Reports";
        if (lower.contains("price") || lower.contains("fee")) return "Pricing";
        return "General";
    }
}
