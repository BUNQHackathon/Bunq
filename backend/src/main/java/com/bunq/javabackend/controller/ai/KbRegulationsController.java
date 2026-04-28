package com.bunq.javabackend.controller.ai;

import com.bunq.javabackend.dto.response.kb.KbRegulationDetailDTO;
import com.bunq.javabackend.dto.response.kb.KbRegulationSummaryDTO;
import com.bunq.javabackend.service.ai.kb.KbRegulationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/kb/regulations")
@RequiredArgsConstructor
public class KbRegulationsController {

    private final KbRegulationService kbRegulationService;

    @GetMapping
    public List<KbRegulationSummaryDTO> list() {
        return kbRegulationService.list();
    }

    @GetMapping("/{id}")
    public KbRegulationDetailDTO get(@PathVariable String id) {
        return kbRegulationService.get(id);
    }
}
