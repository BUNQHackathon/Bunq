package com.bunq.javabackend.service.launch;

import com.bunq.javabackend.dto.request.PipelineStartRequestDTO;
import com.bunq.javabackend.dto.response.JurisdictionOverviewDTO;
import com.bunq.javabackend.dto.response.JurisdictionTriageDTO;
import com.bunq.javabackend.model.gap.Gap;
import com.bunq.javabackend.model.gap.RecommendedAction;
import com.bunq.javabackend.model.enums.RunStatus;
import com.bunq.javabackend.model.launch.JurisdictionRun;
import com.bunq.javabackend.model.launch.Launch;
import com.bunq.javabackend.repository.GapRepository;
import com.bunq.javabackend.repository.JurisdictionRunRepository;
import com.bunq.javabackend.repository.LaunchRepository;
import com.bunq.javabackend.repository.SessionRepository;
import com.bunq.javabackend.service.AutoDocService;
import com.bunq.javabackend.service.pipeline.PipelineOrchestrator;
import com.bunq.javabackend.service.session.SessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class JurisdictionOverviewService {

    private final JurisdictionRunRepository jurisdictionRunRepository;
    private final LaunchRepository launchRepository;
    private final GapRepository gapRepository;
    private final SessionService sessionService;
    private final SessionRepository sessionRepository;
    private final AutoDocService autoDocService;
    private final PipelineOrchestrator pipelineOrchestrator;

    public List<JurisdictionOverviewDTO> overview() {
        var runs = jurisdictionRunRepository.findAll();
        Map<String, List<JurisdictionRun>> byCode = runs.stream()
                .collect(Collectors.groupingBy(JurisdictionRun::getJurisdictionCode));
        return byCode.entrySet().stream()
                .map(e -> {
                    String code = e.getKey();
                    List<JurisdictionRun> group = e.getValue();
                    int launchCount = (int) group.stream()
                            .map(JurisdictionRun::getLaunchId)
                            .distinct()
                            .count();
                    String worst = worstVerdict(group);
                    return JurisdictionOverviewDTO.builder()
                            .code(code)
                            .aggregateVerdict(worst)
                            .worstVerdict(worst)
                            .launchCount(launchCount)
                            .build();
                })
                .sorted(Comparator.comparing(JurisdictionOverviewDTO::code))
                .toList();
    }

    public JurisdictionTriageDTO triage(String code, boolean readOnly) {
        var runs = jurisdictionRunRepository.findByJurisdiction(code);
        List<JurisdictionTriageDTO.KeepCard> keep = new ArrayList<>();
        List<JurisdictionTriageDTO.ModifyCard> modify = new ArrayList<>();
        List<JurisdictionTriageDTO.DropCard> drop = new ArrayList<>();
        List<JurisdictionTriageDTO.PendingCard> pending = new ArrayList<>();

        // Track which launchIds already have a run for this jurisdiction
        Set<String> analyzedLaunchIds = new java.util.HashSet<>();

        for (JurisdictionRun run : runs) {
            var launchOpt = launchRepository.findById(run.getLaunchId());
            if (launchOpt.isEmpty()) continue;
            var launch = launchOpt.get();
            analyzedLaunchIds.add(run.getLaunchId());

            String verdict = run.getVerdict();
            // Runs with no verdict (PENDING/RUNNING) go to pending bucket
            if (verdict == null) {
                pending.add(new JurisdictionTriageDTO.PendingCard(
                        run.getLaunchId(), launch.getName(), launch.getKind(), run.getStatus() != null ? run.getStatus().name() : null));
                continue;
            }

            List<Gap> gaps = run.getCurrentSessionId() != null
                    ? gapRepository.findBySessionId(run.getCurrentSessionId())
                    : List.of();

            if ("GREEN".equalsIgnoreCase(verdict)) {
                keep.add(new JurisdictionTriageDTO.KeepCard(run.getLaunchId(), launch.getName(), launch.getKind()));
            } else if ("AMBER".equalsIgnoreCase(verdict)) {
                List<String> changes = gaps.stream()
                        .flatMap(g -> g.getRecommendedActions() == null
                                ? java.util.stream.Stream.empty()
                                : g.getRecommendedActions().stream())
                        .map(RecommendedAction::getAction)
                        .filter(a -> a != null && !a.isBlank())
                        .collect(Collectors.toCollection(LinkedHashSet::new))
                        .stream()
                        .limit(10)
                        .toList();
                modify.add(new JurisdictionTriageDTO.ModifyCard(run.getLaunchId(), launch.getName(), launch.getKind(), changes));
            } else if ("RED".equalsIgnoreCase(verdict)) {
                String reason = gaps.stream()
                        .filter(g -> g.getResidualRisk() != null)
                        .max(Comparator.comparingDouble(Gap::getResidualRisk))
                        .map(Gap::getNarrative)
                        .or(() -> gaps.stream().findFirst().map(Gap::getNarrative))
                        .orElse("");
                drop.add(new JurisdictionTriageDTO.DropCard(run.getLaunchId(), launch.getName(), launch.getKind(), reason));
            }
        }

        // For launches with no run at all for this jurisdiction: create PENDING run and fire pipeline
        if (!readOnly) {
            List<Launch> allLaunches = launchRepository.findAll();
            for (Launch launch : allLaunches) {
                if (analyzedLaunchIds.contains(launch.getId())) continue;

                // Concurrency guard: re-check existence before creating
                if (jurisdictionRunRepository.findByLaunchIdAndCode(launch.getId(), code).isPresent()) continue;

                var session = sessionService.createSessionForJurisdiction(launch.getId(), code);

                var docs = autoDocService.forJurisdiction(code);
                var docIds = docs.stream().map(com.bunq.javabackend.model.document.Document::getId).toList();
                session.setDocumentIds(docIds);
                sessionRepository.save(session);

                JurisdictionRun newRun = JurisdictionRun.builder()
                        .launchId(launch.getId())
                        .jurisdictionCode(code)
                        .currentSessionId(session.getId())
                        .status(RunStatus.PENDING)
                        .verdict(null)
                        .gapsCount(0)
                        .sanctionsHits(0)
                        .lastRunAt(Instant.now().toString())
                        .build();
                jurisdictionRunRepository.save(newRun);

                PipelineStartRequestDTO req = PipelineStartRequestDTO.builder()
                        .counterparties(List.of())
                        .launchId(launch.getId())
                        .jurisdictionCode(code)
                        .build();
                pipelineOrchestrator.start(session.getId(), req);

                pending.add(new JurisdictionTriageDTO.PendingCard(
                        launch.getId(), launch.getName(), launch.getKind(), "PENDING"));
            }
        }

        return new JurisdictionTriageDTO(code, keep, modify, drop, pending);
    }

    private String worstVerdict(List<JurisdictionRun> runs) {
        boolean hasRed = runs.stream().anyMatch(r -> "RED".equals(r.getVerdict()));
        if (hasRed) return "RED";
        boolean hasAmber = runs.stream().anyMatch(r -> "AMBER".equals(r.getVerdict()));
        if (hasAmber) return "AMBER";
        boolean hasGreen = runs.stream().anyMatch(r -> "GREEN".equals(r.getVerdict()));
        if (hasGreen) return "GREEN";
        return null;
    }
}
