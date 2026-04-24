package com.bunq.javabackend.util;

import java.util.UUID;

public final class IdGenerator {

    private IdGenerator() {}

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
}