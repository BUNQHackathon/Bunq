package com.bunq.javabackend.controller;

import com.bunq.javabackend.exception.NotFoundException;
import com.bunq.javabackend.service.ReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping("/sessions")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;

    @GetMapping("/{sessionId}/report.pdf")
    public ResponseEntity<Void> downloadReport(@PathVariable String sessionId) {
        String presignedUrl = reportService.presignExistingReport(sessionId);
        return ResponseEntity.status(302).location(URI.create(presignedUrl)).build();
    }
}
