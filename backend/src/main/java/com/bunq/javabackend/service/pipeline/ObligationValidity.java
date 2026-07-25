package com.bunq.javabackend.service.pipeline;

import com.bunq.javabackend.model.obligation.Obligation;
import com.bunq.javabackend.model.obligation.ObligationSource;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Deterministic (no-LLM, no-AWS) pre-pass that drops extraction junk before the obligation
 * ever reaches the relevance filter: empty/degenerate records, obligations whose obligated
 * party is not the bank, and UI/tool-manual fragments lifted from the reporting-tool manual.
 *
 * Conservative by design: any ambiguity results in KEEP (empty Optional).
 */
public final class ObligationValidity {

    private ObligationValidity() {
    }

    public enum DropReason {
        EMPTY,
        WRONG_PARTY,
        TOOL_MANUAL
    }

    /** Subject terms that indicate the obligated party IS the bank/regulated entity — always KEEP. */
    private static final List<String> ALLOW_PARTY_TERMS = List.of(
            "obliged entit",
            "intermediar",
            "bank",
            "payment institution",
            "e-money",
            "emi",
            "aml function",
            "mlro",
            "compliance",
            "financial institution",
            "reporting entit",
            "obligated",
            "destinatari",
            "segnalant"
    );

    /** Subject terms that indicate the obligated party is NOT the bank — DROP unless allow-listed above. */
    private static final List<String> DENY_PARTY_TERMS = List.of(
            "customer",
            "client",
            "the administration",
            "public administration",
            "self-regulatory",
            "comitato di sicurezza finanziaria",
            "financial security committee",
            "minister",
            "ministry",
            "judicial authority",
            "guardia di finanza",
            "nucleo speciale di polizia valutaria",
            "special financial police",
            "uif",
            "dia",
            "professional association",
            "guild",
            "ordini professional",
            "poste italiane",
            "data subject"
    );

    /**
     * Markers before which the subject's qualifiers/objects trail off (e.g. "guardians OF client
     * assets"). The DENY check only looks at the text before the first such marker — who the
     * obligated party IS — so a denied term appearing only in a trailing qualifier (e.g. "client"
     * in "guardians of client assets") never causes a false drop.
     */
    private static final List<String> SUBJECT_HEAD_MARKERS = List.of(
            " of ", " for ", " to ", " from ", " in ", " with "
    );

    /** Left-word-boundary patterns, precompiled once from {@link #ALLOW_PARTY_TERMS}. */
    private static final List<Pattern> ALLOW_PARTY_PATTERNS = compileLeftBoundary(ALLOW_PARTY_TERMS);

    /** Left-word-boundary patterns, precompiled once from {@link #DENY_PARTY_TERMS}. */
    private static final List<Pattern> DENY_PARTY_PATTERNS = compileLeftBoundary(DENY_PARTY_TERMS);

    /** Source document/regulation markers identifying the Infostat/SARA reporting-tool manual. */
    private static final List<String> TOOL_MANUAL_SOURCE_TERMS = List.of(
            "infostat",
            "sara-manual",
            "sara manual"
    );

    /** UI/tool-manual action text patterns (case-insensitive). */
    private static final Pattern TOOL_MANUAL_ACTION_PATTERN = Pattern.compile(
            "'edit' function|imported data|\\bbutton\\b|click|select the field|upload the file|drop-?down|toolbar",
            Pattern.CASE_INSENSITIVE
    );

    public static Optional<DropReason> check(Obligation o) {
        String action = o.getAction();
        String subject = o.getSubject();

        if (isBlank(action) || isBlank(subject)) {
            return Optional.of(DropReason.EMPTY);
        }

        // ALLOW is checked against the full subject: erring toward a match here only over-KEEPs,
        // which is the safe direction.
        boolean allowed = ALLOW_PARTY_PATTERNS.stream().anyMatch(p -> p.matcher(subject).find());
        if (!allowed) {
            // DENY is checked against the subject HEAD only (text before the first qualifier
            // marker), so a denied term mentioned only in a trailing qualifier/object doesn't
            // cause a false drop — e.g. "guardians of client assets" -> head "guardians".
            String head = subjectHead(subject);
            boolean denied = DENY_PARTY_PATTERNS.stream().anyMatch(p -> p.matcher(head).find());
            if (denied) {
                return Optional.of(DropReason.WRONG_PARTY);
            }
        }

        if (isToolManual(o)) {
            return Optional.of(DropReason.TOOL_MANUAL);
        }

        return Optional.empty();
    }

    private static String subjectHead(String subject) {
        String lower = subject.toLowerCase();
        int earliest = -1;
        for (String marker : SUBJECT_HEAD_MARKERS) {
            int idx = lower.indexOf(marker);
            if (idx >= 0 && (earliest == -1 || idx < earliest)) {
                earliest = idx;
            }
        }
        return earliest == -1 ? subject : subject.substring(0, earliest);
    }

    /**
     * Length-based boundary rule, applied uniformly to both ALLOW and DENY lists:
     * <ul>
     *   <li>term length &le; 3 &rarr; BOTH boundaries ({@code \bTERM\b}). Short acronyms
     *       ("uif", "dia", "emi") collide as bare prefixes inside ordinary words — "dia" inside
     *       "Diamond", "emi" inside "systemic"/"academic" — so they must match as whole words
     *       only.</li>
     *   <li>term length &gt; 3 &rarr; LEFT boundary only ({@code \bTERM}). Longer terms include
     *       deliberate truncated prefixes designed to match inflected forms — "entit" -&gt;
     *       entity/entities, "intermediar" -&gt; intermediary/intermediaries, "destinatari",
     *       "segnalant" — and Italian inflections (e.g. "minister" -&gt; "Ministero"); a trailing
     *       boundary would silently stop those matches, so only the left side (which is what
     *       actually fixes bare-substring collisions, e.g. "dia" mid-word in "custodians") is
     *       enforced.</li>
     * </ul>
     */
    private static Pattern leftWordBoundary(String term) {
        String trailingBoundary = term.length() <= 3 ? "\\b" : "";
        return Pattern.compile("\\b" + Pattern.quote(term) + trailingBoundary, Pattern.CASE_INSENSITIVE);
    }

    private static List<Pattern> compileLeftBoundary(List<String> terms) {
        return terms.stream().map(ObligationValidity::leftWordBoundary).toList();
    }

    private static boolean isToolManual(Obligation o) {
        ObligationSource source = o.getSource();
        if (source != null && source.getRegulation() != null) {
            String regulationLower = source.getRegulation().toLowerCase();
            if (TOOL_MANUAL_SOURCE_TERMS.stream().anyMatch(regulationLower::contains)) {
                return true;
            }
        }
        if (o.getDocumentId() != null) {
            String documentIdLower = o.getDocumentId().toLowerCase();
            if (TOOL_MANUAL_SOURCE_TERMS.stream().anyMatch(documentIdLower::contains)) {
                return true;
            }
        }
        return TOOL_MANUAL_ACTION_PATTERN.matcher(o.getAction()).find();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
