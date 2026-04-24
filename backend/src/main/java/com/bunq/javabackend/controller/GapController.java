package com.bunq.javabackend.controller;

import com.bunq.javabackend.dto.request.ScoreGapsRequestDTO;
import com.bunq.javabackend.dto.response.GapResponseDTO;
import com.bunq.javabackend.service.GapService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/gaps")
@RequiredArgsConstructor
public class GapController {

    private final GapService gapService;

    @PostMapping("/score")
    public ResponseEntity<Void> score(@Valid @RequestBody ScoreGapsRequestDTO request) {
        gapService.score(request);
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/list")
    public ResponseEntity<List<GapResponseDTO>> list(@RequestParam String sessionId) {
        return ResponseEntity.ok(gapService.list(sessionId));
    }
}
