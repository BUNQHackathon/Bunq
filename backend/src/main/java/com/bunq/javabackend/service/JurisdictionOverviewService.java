package com.bunq.javabackend.service;

import com.bunq.javabackend.dto.response.JurisdictionOverviewDTO;
import com.bunq.javabackend.dto.response.JurisdictionTriageDTO;
import com.bunq.javabackend.model.gap.Gap;
import com.bunq.javabackend.model.gap.RecommendedAction;
import com.bunq.javabackend.model.launch.JurisdictionRun;
import com.bunq.javabackend.repository.GapRepository;
import com.bunq.javabackend.repository.JurisdictionRunRepository;
import com.bunq.javabackend.repository.LaunchRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class JurisdictionOverviewService {

    private final JurisdictionRunRepository jurisdictionRunRepository;
    private final LaunchRepository launchRepository;
    private final GapRepository gapRepository;

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

    public JurisdictionTriageDTO triage(String code) {
        var runs = jurisdictionRunRepository.findByJurisdiction(code);
        List<JurisdictionTriageDTO.KeepCard> keep = new ArrayList<>();
        List<JurisdictionTriageDTO.ModifyCard> modify = new ArrayList<>();
        List<JurisdictionTriageDTO.DropCard> drop = new ArrayList<>();

        for (JurisdictionRun run : runs) {
            var launchOpt = launchRepository.findById(run.getLaunchId());
            if (launchOpt.isEmpty()) continue;
            var launch = launchOpt.get();
            String verdict = run.getVerdict();
            if (verdict == null) continue;

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

        return new JurisdictionTriageDTO(code, keep, modify, drop);
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
