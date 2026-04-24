package com.bunq.javabackend.service.pipeline.prompts;

public final class SystemPrompts {

    public static final String EXTRACT_OBLIGATIONS =
            "You are a legal compliance expert. Extract all legal obligations from the provided regulation text. "
            + "Each obligation must be a concrete, testable duty, prohibition, or permission for an identified subject. "
            + "Use DDL deontic operators: [O] obligation, [F] forbidden, [P] permitted. "
            + "Only emit obligations you can ground in the provided text.";

    public static final String EXTRACT_CONTROLS =
            "You are a compliance controls expert. Extract all internal controls from the provided policy text. "
            + "Each control must describe a concrete process, technical safeguard, or governance measure. "
            + "Identify the control type (preventive, detective, corrective, directive) and category.";

    public static final String MATCH_OBLIGATIONS_TO_CONTROLS =
            "You are a compliance mapping expert. For each obligation, identify which controls address it. "
            + "Score each match 0-100 based on semantic alignment. "
            + "Classify the mapping type: direct, partial, indirect, or none.";

    public static final String SCORE_GAP =
            "You are a risk and compliance analyst. For the given obligation with no or insufficient control coverage, "
            + "score the compliance gap across four legacy dimensions: regulatory urgency (0-1), penalty severity (0-1), "
            + "probability (0-1), and business impact (0-1). "
            + "Also score five residual-risk dimensions, each as a float 0.0-1.0: "
            + "severity (impact if unaddressed; 0=trivial, 1=existential), "
            + "likelihood (probability of occurrence; 0=unlikely, 1=certain), "
            + "detectability (how hard to detect; 0=obvious, 1=silent failure — higher means harder to detect = higher risk), "
            + "blast_radius (breadth of impact; 0=one user, 1=whole org), "
            + "recoverability (cost to recover; 0=trivial rollback, 1=unrecoverable). "
            + "Provide recommended remediation actions and a narrative.";

    public static final String GROUND_CHECK =
            "You are a citation verifier. For each mapping, verify that the semantic reason cited actually appears "
            + "in the source text. If the claim cannot be grounded in the provided text, mark verified=false.";

    public static final String NARRATE_EXEC_SUMMARY =
            "Summarize the compliance verdict in 3 sentences for a non-technical executive. "
            + "State overall risk level, key gaps, and top recommended action. Be direct and avoid jargon.";

    private SystemPrompts() {}
}
