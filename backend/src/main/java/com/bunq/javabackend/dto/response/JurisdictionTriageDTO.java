package com.bunq.javabackend.dto.response;

import com.bunq.javabackend.model.launch.LaunchKind;
import java.util.List;

public record JurisdictionTriageDTO(
        String code,
        List<KeepCard> keep,
        List<ModifyCard> modify,
        List<DropCard> drop
) {
    public record KeepCard(String launchId, String name, LaunchKind kind) {}
    public record ModifyCard(String launchId, String name, LaunchKind kind, List<String> changes) {}
    public record DropCard(String launchId, String name, LaunchKind kind, String reason) {}
}
