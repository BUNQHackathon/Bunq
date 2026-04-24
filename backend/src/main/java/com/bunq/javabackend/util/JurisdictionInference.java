package com.bunq.javabackend.util;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

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

    /** Keyword → ISO-2 jurisdiction map used by inferFromText. */
    private static final Map<String, String> KEYWORD_MAP;
    /** Single-char symbols that need plain contains() rather than word-boundary regex. */
    private static final Map<String, String> SYMBOL_MAP = Map.of(
            "£", "UK",
            "$", "US"
    );

    static {
        Map<String, String> m = new java.util.LinkedHashMap<>();
        // NL
        m.put("nl", "NL"); m.put("netherlands", "NL"); m.put("dutch", "NL"); m.put("dnb", "NL");
        // DE
        m.put("de", "DE"); m.put("germany", "DE"); m.put("german", "DE"); m.put("deutsch", "DE"); m.put("bafin", "DE");
        // FR
        m.put("fr", "FR"); m.put("france", "FR"); m.put("french", "FR"); m.put("acpr", "FR");
        // UK
        m.put("uk", "UK"); m.put("gb", "UK"); m.put("britain", "UK"); m.put("england", "UK");
        m.put("united kingdom", "UK"); m.put("fca", "UK"); m.put("gbp", "UK");
        // US
        m.put("us", "US"); m.put("usa", "US"); m.put("united states", "US"); m.put("america", "US");
        m.put("american", "US"); m.put("occ", "US"); m.put("sec", "US"); m.put("usd", "US");
        // IE
        m.put("ie", "IE"); m.put("ireland", "IE"); m.put("irish", "IE"); m.put("cbi", "IE");
        KEYWORD_MAP = java.util.Collections.unmodifiableMap(m);
    }

    /**
     * Infer jurisdiction codes from free-text by scanning for well-known keywords.
     *
     * @param text arbitrary user text (question, hint, etc.)
     * @return set of matched ISO-2 jurisdiction codes; empty if none found or input is blank
     */
    public static Set<String> inferFromText(String text) {
        if (text == null || text.isBlank()) return Set.of();
        String lower = text.toLowerCase();
        Set<String> results = new LinkedHashSet<>();
        // Symbol checks (non-word chars; can't use \b)
        for (Map.Entry<String, String> e : SYMBOL_MAP.entrySet()) {
            if (lower.contains(e.getKey())) {
                results.add(e.getValue());
            }
        }
        // Word-boundary keyword checks
        for (Map.Entry<String, String> e : KEYWORD_MAP.entrySet()) {
            String kw = e.getKey();
            Pattern p = Pattern.compile("\\b" + Pattern.quote(kw) + "\\b");
            if (p.matcher(lower).find()) {
                results.add(e.getValue());
            }
        }
        return results;
    }

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
