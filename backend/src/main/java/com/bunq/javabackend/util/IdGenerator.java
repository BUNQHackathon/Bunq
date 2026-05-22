package com.bunq.javabackend.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

public final class IdGenerator {

    private IdGenerator() {}

    // ── Session-scoped content-addressable IDs ───────────────────────────────
    // IDs are scoped to a session: same content in different sessions produces
    // different IDs, so each session owns its own physical DynamoDB rows.
    // Cross-session mapping cache will no longer hit — this is intentional.

    /**
     * Session-scoped obligation ID based on session + document + deontic subject + action.
     * Prefix "OBL-" distinguishes it from legacy random IDs ("obl-").
     */
    public static String obligationId(String sessionId, String documentId, String subject, String action) {
        String key = nullToEmpty(sessionId) + " " + nullToEmpty(documentId) + " " + nullToEmpty(subject) + " " + nullToEmpty(action);
        return "OBL-" + sha256Hex(key).substring(0, 24);
    }

    /**
     * Session-scoped control ID based on session + document + description.
     * Prefix "CTRL-" distinguishes it from legacy random IDs ("ctrl-").
     */
    public static String controlId(String sessionId, String documentId, String description) {
        String key = nullToEmpty(sessionId) + " " + nullToEmpty(documentId) + " " + nullToEmpty(description);
        return "CTRL-" + sha256Hex(key).substring(0, 24);
    }

    // ── Random IDs (used for entities without stable content keys) ───────────

    public static String generateObligationId() {
        return "obl-" + UUID.randomUUID();
    }

    public static String generateControlId() {
        return "ctrl-" + UUID.randomUUID();
    }

    public static String generateMappingId() {
        return "map-" + UUID.randomUUID();
    }

    public static String generateGapId() {
        return "gap-" + UUID.randomUUID();
    }

    public static String generateSessionId() {
        return UUID.randomUUID().toString();
    }

    public static String generateEvidenceId() {
        return "ev-" + UUID.randomUUID();
    }

    public static String generateSanctionsHitId() {
        return "hit-" + UUID.randomUUID();
    }

    public static String generateAuditEntryId() {
        return "audit-" + UUID.randomUUID();
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
