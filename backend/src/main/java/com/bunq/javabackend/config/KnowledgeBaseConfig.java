package com.bunq.javabackend.config;

import com.bunq.javabackend.model.enums.KbType;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Configuration
@ConfigurationProperties("aws.bedrock.kb")
@Getter
@Setter
public class KnowledgeBaseConfig {

    private String regulationsId;
    private String policiesId;
    private String controlsId;
    private String regulationsDataSourceId;
    private String policiesDataSourceId;
    private String controlsDataSourceId;
    private String regulationsSourceBucket;
    private String policiesSourceBucket;
    private String controlsSourceBucket;
    private List<Entry> entries = new ArrayList<>();

    public String getRegulationsId() {
        return resolveId(KbType.REGULATIONS, regulationsId);
    }

    public String getPoliciesId() {
        return resolveId(KbType.POLICIES, policiesId);
    }

    public String getControlsId() {
        return resolveId(KbType.CONTROLS, controlsId);
    }

    public List<Entry> getConfiguredEntries() {
        List<Entry> source = entries == null || entries.isEmpty() ? fallbackEntries() : entries;
        return source.stream()
                .map(this::resolvedEntry)
                .filter(entry -> hasText(entry.getKnowledgeBaseId()))
                .toList();
    }

    public Optional<Entry> findByKnowledgeBaseId(String knowledgeBaseId) {
        if (!hasText(knowledgeBaseId)) {
            return Optional.empty();
        }
        return getConfiguredEntries().stream()
                .filter(entry -> knowledgeBaseId.trim().equals(entry.getKnowledgeBaseId()))
                .findFirst();
    }

    public Optional<Entry> findByKbType(KbType kbType) {
        if (kbType == null) {
            return Optional.empty();
        }
        return getConfiguredEntries().stream()
                .filter(entry -> kbType == entry.getKbType())
                .findFirst();
    }

    private String resolveId(KbType kbType, String legacyId) {
        if (hasText(legacyId)) {
            return legacyId;
        }
        return rawEntryFor(kbType)
                .map(Entry::getKnowledgeBaseId)
                .filter(KnowledgeBaseConfig::hasText)
                .orElse(null);
    }

    private Optional<Entry> rawEntryFor(KbType kbType) {
        if (entries == null || entries.isEmpty()) {
            return Optional.empty();
        }
        return entries.stream()
                .filter(entry -> kbType == entry.getKbType())
                .findFirst();
    }

    private Entry resolvedEntry(Entry source) {
        String legacyId = legacyIdFor(source.getKbType());
        Entry entry = new Entry();
        entry.setKey(source.getKey());
        entry.setLabel(source.getLabel());
        entry.setKbType(source.getKbType());
        entry.setDefault(source.isDefault());
        entry.setKnowledgeBaseId(hasText(legacyId) ? legacyId : source.getKnowledgeBaseId());
        entry.setDataSourceId(resolveDataSourceId(source));
        entry.setSourceBucket(resolveSourceBucket(source));
        return entry;
    }

    private String resolveDataSourceId(Entry source) {
        String legacyValue = legacyDataSourceIdFor(source.getKbType());
        return hasText(legacyValue) ? legacyValue : source.getDataSourceId();
    }

    private String resolveSourceBucket(Entry source) {
        String legacyValue = legacySourceBucketFor(source.getKbType());
        return hasText(legacyValue) ? legacyValue : source.getSourceBucket();
    }

    private String legacyIdFor(KbType kbType) {
        if (kbType == KbType.REGULATIONS) {
            return regulationsId;
        }
        if (kbType == KbType.POLICIES) {
            return policiesId;
        }
        if (kbType == KbType.CONTROLS) {
            return controlsId;
        }
        return null;
    }

    private String legacyDataSourceIdFor(KbType kbType) {
        if (kbType == KbType.REGULATIONS) {
            return regulationsDataSourceId;
        }
        if (kbType == KbType.POLICIES) {
            return policiesDataSourceId;
        }
        if (kbType == KbType.CONTROLS) {
            return controlsDataSourceId;
        }
        return null;
    }

    private String legacySourceBucketFor(KbType kbType) {
        if (kbType == KbType.REGULATIONS) {
            return regulationsSourceBucket;
        }
        if (kbType == KbType.POLICIES) {
            return policiesSourceBucket;
        }
        if (kbType == KbType.CONTROLS) {
            return controlsSourceBucket;
        }
        return null;
    }

    private static List<Entry> fallbackEntries() {
        return List.of(
                entry("regulations", "Regulations", KbType.REGULATIONS),
                entry("policies", "bunq policies", KbType.POLICIES),
                entry("controls", "Internal controls", KbType.CONTROLS)
        );
    }

    private static Entry entry(String key, String label, KbType kbType) {
        Entry entry = new Entry();
        entry.setKey(key);
        entry.setLabel(label);
        entry.setKbType(kbType);
        return entry;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    @Getter
    @Setter
    public static class Entry {
        private String key;
        private String label;
        private String knowledgeBaseId;
        private String dataSourceId;
        private String sourceBucket;
        private KbType kbType;
        private boolean defaultEntry;

        public boolean isDefault() {
            return defaultEntry;
        }

        public void setDefault(boolean defaultEntry) {
            this.defaultEntry = defaultEntry;
        }
    }
}
