package com.bunq.javabackend.util;

import java.util.List;
import java.util.Set;

/**
 * Stateless utility for inferring document jurisdictions from filenames and for
 * checking whether a document is available for a given jurisdiction code.
 */
public final class JurisdictionInference {

    /** EU member-state codes — EU-tagged docs are available to all of these. */
    private static final Set<String> EU_MEMBERS = Set.of(
            "NL", "DE", "FR", "IT", "ES", "BE", "AT", "PT", "IE", "LU",
            "GR", "CY", "MT", "SI", "SK", "EE", "LV", "LT", "FI", "DK",
            "SE", "BG", "RO", "HR", "CZ", "HU", "PL"
    );

    private JurisdictionInference() {}

    /**
     * Infer the jurisdiction tag list from a document filename.
     * First match wins — rules are evaluated in priority order.
     *
     * @param filename raw filename (any case)
     * @return immutable list of jurisdiction codes, or empty list for global/unknown
     */
    public static List<String> inferFromFilename(String filename) {
        if (filename == null) return List.of();
        String f = filename.toLowerCase();

        if (f.contains("mica") || f.contains("celex_32023r1114")) return List.of("EU");
        if (f.contains("gdpr") || f.contains("celex_32016r0679"))  return List.of("EU");
        if (f.contains("psd2") || f.contains("celex_32015l2366"))  return List.of("EU");
        if (f.contains("amld"))                                      return List.of("EU");
        if (f.contains("dnb") || f.contains("wwft"))                return List.of("NL");
        if (f.contains("bafin") || f.contains("zag") || f.contains("kreditwesen")) return List.of("DE");
        if (f.contains("bsa") || f.contains("ofac") || f.contains("fincen")
                || f.contains("occ") || f.contains("sr-"))          return List.of("US");
        if (f.contains("bunq"))                                      return List.of("NL");

        return List.of(); // global / unknown
    }

    /**
     * Expands a jurisdiction code into the filter list used for KB retrieval.
     * EU members get both their own code and "EU" so they match EU-tagged chunks.
     */
    public static List<String> expandForRetrieval(String code) {
        if (code == null || code.isBlank()) return List.of();
        String upper = code.toUpperCase();
        if (EU_MEMBERS.contains(upper)) return List.of(upper, "EU");
        return List.of(upper);
    }

    /**
     * Returns true if a document tagged with {@code docJurisdictions} should be
     * included in results for {@code requestedCode}.
     *
     * <ul>
     *   <li>Empty tag list → global → available everywhere.</li>
     *   <li>Exact match → available.</li>
     *   <li>EU member requesting and doc tagged "EU" → available.</li>
     * </ul>
     */
    public static boolean isAvailableFor(List<String> docJurisdictions, String requestedCode) {
        if (docJurisdictions == null || docJurisdictions.isEmpty()) return true;
        String code = requestedCode == null ? "" : requestedCode.toUpperCase();
        if (docJurisdictions.contains(code)) return true;
        if (EU_MEMBERS.contains(code) && docJurisdictions.contains("EU")) return true;
        return false;
    }
}
