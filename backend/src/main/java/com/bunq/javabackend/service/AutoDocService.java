package com.bunq.javabackend.service;

import com.bunq.javabackend.model.document.Document;
import com.bunq.javabackend.repository.DocJurisdictionRepository;
import com.bunq.javabackend.repository.DocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class AutoDocService {

    private static final Set<String> ALLOWED_KINDS = Set.of("policy", "regulation", "brief");
    private static final Set<String> EU_MEMBER_CODES = Set.of("NL", "DE", "FR", "IE");

    private final DocumentRepository documentRepository;
    private final DocJurisdictionRepository docJurisdictionRepository;

    public List<Document> forJurisdiction(String jurisdictionCode) {
        String code = jurisdictionCode == null ? "" : jurisdictionCode.toUpperCase();

        Set<String> docIds = new LinkedHashSet<>();
        docJurisdictionRepository.findByJurisdiction(code).forEach(item -> docIds.add(item.getDocumentId()));

        if (EU_MEMBER_CODES.contains(code)) {
            docJurisdictionRepository.findByJurisdiction("EU").forEach(item -> docIds.add(item.getDocumentId()));
        }

        if (docIds.isEmpty()) return List.of();

        return documentRepository.findByIds(new ArrayList<>(docIds)).stream()
                .filter(doc -> ALLOWED_KINDS.contains(doc.getKind()))
                .sorted(Comparator.comparing(
                        Document::getLastUsedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())
                ))
                .toList();
    }
}
