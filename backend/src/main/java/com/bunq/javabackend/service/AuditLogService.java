package com.bunq.javabackend.service;

import com.bunq.javabackend.model.audit.AuditLogEntry;
import com.bunq.javabackend.repository.AuditLogRepository;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuditLogService {

    private final AuditLogRepository repo;
    private final ObjectMapper mapper;

    public AuditLogEntry append(String sessionId, String mappingId, String action,
                                String actor, Map<String, Object> payload) throws Exception {
        String prevHash = repo.findLatestBySessionId(sessionId)
                .map(AuditLogEntry::getEntryHash).orElse("");
        String payloadJson = mapper.writeValueAsString(payload == null ? Map.of() : payload);
        Instant now = Instant.now();
        String id = UUID.randomUUID().toString();

        String canonical = "action=" + nullSafe(action)
                + "|actor=" + nullSafe(actor)
                + "|id=" + id
                + "|mappingId=" + nullSafe(mappingId)
                + "|payload=" + payloadJson
                + "|prevHash=" + prevHash
                + "|sessionId=" + sessionId
                + "|timestamp=" + now;
        String entryHash = sha256Hex(canonical);

        AuditLogEntry entry = AuditLogEntry.builder()
                .id(id).sessionId(sessionId).mappingId(mappingId)
                .action(action).actor(actor).timestamp(now)
                .payloadJson(payloadJson).prevHash(prevHash).entryHash(entryHash)
                .build();
        repo.saveIfNotExists(entry);
        return entry;
    }

    private String nullSafe(String s) {
        return s == null ? "" : s;
    }

    private String sha256Hex(String input) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
