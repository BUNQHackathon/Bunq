package com.bunq.javabackend.controller.documents;

import com.bunq.javabackend.dto.request.DocumentFinalizeRequest;
import com.bunq.javabackend.dto.request.DocumentPresignRequest;
import com.bunq.javabackend.dto.response.DocumentFinalizeResponse;
import com.bunq.javabackend.dto.response.DocumentListResponse;
import com.bunq.javabackend.dto.response.DocumentPresignResponse;
import com.bunq.javabackend.dto.response.DocumentResponseDTO;
import com.bunq.javabackend.service.documents.DocumentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/documents")
@RequiredArgsConstructor
public class DocumentsController {

    private final DocumentService documentService;

    @PostMapping("/presign")
    public ResponseEntity<DocumentPresignResponse> presign(@Valid @RequestBody DocumentPresignRequest request) {
        return ResponseEntity.ok(documentService.presign(request));
    }

    @PostMapping("/finalize")
    public ResponseEntity<DocumentFinalizeResponse> finalize(@Valid @RequestBody DocumentFinalizeRequest request) {
        return ResponseEntity.ok(documentService.finalize(request));
    }

    @GetMapping
    public ResponseEntity<DocumentListResponse> list(
            @RequestParam(required = false) String kind,
            @RequestParam(required = false, defaultValue = "50") int limit) {
        return ResponseEntity.ok(documentService.list(kind, limit));
    }

    @GetMapping("/{id}")
    public ResponseEntity<DocumentResponseDTO> get(@PathVariable String id) {
        return ResponseEntity.ok(documentService.get(id));
    }
}
