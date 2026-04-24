package com.bunq.javabackend.helper.mapper;

import com.bunq.javabackend.dto.response.DocumentResponseDTO;
import com.bunq.javabackend.model.document.Document;

public final class DocumentMapper {

    private DocumentMapper() {}

    public static DocumentResponseDTO toDto(Document doc) {
        return DocumentResponseDTO.builder()
                .id(doc.getId())
                .filename(doc.getFilename())
                .contentType(doc.getContentType())
                .sizeBytes(doc.getSizeBytes())
                .kind(doc.getKind())
                .firstSeenAt(doc.getFirstSeenAt())
                .lastUsedAt(doc.getLastUsedAt())
                .extractedText(doc.getExtractedText())
                .extractedAt(doc.getExtractedAt())
                .pageCount(doc.getPageCount())
                .obligationsExtracted(doc.isObligationsExtracted())
                .controlsExtracted(doc.isControlsExtracted())
                .build();
    }
}
