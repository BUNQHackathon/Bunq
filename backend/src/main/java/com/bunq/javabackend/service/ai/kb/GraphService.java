package com.bunq.javabackend.service.ai.kb;

import com.bunq.javabackend.dto.response.graph.GraphDataDTO;
import com.bunq.javabackend.dto.response.kb.KbRegulationSummaryDTO;
import com.bunq.javabackend.dto.response.graph.GraphLinkDTO;
import com.bunq.javabackend.dto.response.graph.GraphNodeDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class GraphService {

    private final KbRegulationService kbRegulationService;

    public GraphDataDTO build() {
        List<KbRegulationSummaryDTO> docs = kbRegulationService.list();

        ArrayList<GraphNodeDTO> nodes = new ArrayList<GraphNodeDTO>();
        for (var d : docs) {
            nodes.add(new GraphNodeDTO(d.id(), d.title(), categoryToCat(d.category()), true, 9, d.updated()));
        }

        nodes.add(new GraphNodeDTO("gdpr", "GDPR", "concept", false, 7, ""));
        nodes.add(new GraphNodeDTO("dnb", "DNB", "concept", false, 8, ""));
        nodes.add(new GraphNodeDTO("eupassport", "EU Passport", "concept", false, 6, ""));
        nodes.add(new GraphNodeDTO("kyc", "KYC", "concept", false, 7, ""));
        nodes.add(new GraphNodeDTO("mica", "MiCA", "concept", false, 6, ""));
        nodes.add(new GraphNodeDTO("fatf", "FATF Guidelines", "concept", false, 5, ""));
        nodes.add(new GraphNodeDTO("wwft", "WWFT", "concept", false, 4, ""));

        ArrayList<GraphLinkDTO> links = new ArrayList<GraphLinkDTO>();
        for (var d : docs) {
            String cat = categoryToCat(d.category());
            if (cat.equals("privacy")) {
                links.add(new GraphLinkDTO(d.id(), "gdpr"));
            } else if (cat.equals("aml")) {
                links.add(new GraphLinkDTO(d.id(), "kyc"));
                links.add(new GraphLinkDTO(d.id(), "fatf"));
                links.add(new GraphLinkDTO(d.id(), "wwft"));
            } else if (cat.equals("licensing")) {
                links.add(new GraphLinkDTO(d.id(), "dnb"));
                links.add(new GraphLinkDTO(d.id(), "eupassport"));
                if (d.title().toLowerCase().contains("mica")) {
                    links.add(new GraphLinkDTO(d.id(), "mica"));
                }
            }
        }

        return new GraphDataDTO(nodes, links);
    }

    private static String categoryToCat(String category) {
        return switch (category.toLowerCase()) {
            case "privacy" -> "privacy";
            case "aml" -> "aml";
            case "licensing" -> "licensing";
            case "terms & conditions" -> "terms";
            case "reports" -> "reports";
            case "pricing" -> "pricing";
            default -> "concept";
        };
    }
}
