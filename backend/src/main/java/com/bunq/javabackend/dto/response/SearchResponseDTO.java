package com.bunq.javabackend.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchResponseDTO {

    private String query;
    private List<Hit> documents;
    private List<Hit> sessions;
    private List<Hit> obligations;
    private List<Hit> controls;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Hit {
        /** Group this hit belongs to: document | session | obligation | control */
        private String type;
        private String id;
        private String title;
        private String subtitle;
    }
}
