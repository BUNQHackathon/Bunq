package com.bunq.javabackend.service;

import com.bunq.javabackend.model.document.Document;
import com.bunq.javabackend.repository.DocumentRepository;
import com.bunq.javabackend.util.JurisdictionInference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Returns the subset of the document library that applies to a given jurisdiction.
 * Jurisdiction membership is inferred lazily from each document's filename via
 * {@link JurisdictionInference} — no DB migration required.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AutoDocService {

    private static final Set<String> ALLOWED_KINDS = Set.of("policy", "regulation", "brief");

    private final DocumentRepository documentRepository;

    /**
     * Returns all documents in the library that are applicable to {@code jurisdictionCode},
     * limited to kinds: policy, regulation, brief.
     * Sorted by {@code lastUsedAt} descending (nulls last).
     */
    public List<Document> forJurisdiction(String jurisdictionCode) {
        String code = jurisdictionCode == null ? "" : jurisdictionCode.toUpperCase();

        return documentRepository.scanAll(1000).stream()
                .filter(doc -> ALLOWED_KINDS.contains(doc.getKind()))
                .filter(doc -> {
                    List<String> inferred = JurisdictionInference.inferFromFilename(doc.getFilename());
                    return JurisdictionInference.isAvailableFor(inferred, code);
                })
                .sorted(Comparator.comparing(
                        Document::getLastUsedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())
                ))
                .toList();
    }
}
