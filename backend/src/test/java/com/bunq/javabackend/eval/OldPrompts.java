package com.bunq.javabackend.eval;

/**
 * Baseline snapshot of prompts as they existed before the quote/reason-first refactor.
 * Used for eval comparison only — do not use in production code.
 */
public final class OldPrompts {

  public static final String EXTRACT_OBLIGATIONS = "You are a legal compliance expert. Extract all legal obligations from the provided regulation text. "
      + "Each obligation must be a concrete, testable duty, prohibition, or permission for an identified subject. "
      + "Use DDL deontic operators: [O] obligation, [F] forbidden, [P] permitted. "
      + "Only emit obligations you can ground in the provided text.";

  public static final String EXTRACT_CONTROLS = "You are a compliance controls expert. Extract all internal controls from the provided policy text. "
      + "Each control must describe a concrete process, technical safeguard, or governance measure. "
      + "Identify the control type (preventive, detective, corrective, directive) and category.";

  public static final String MATCH_OBLIGATIONS_TO_CONTROLS = "You are a compliance mapping expert. For each obligation, identify which controls address it. "
      + "Score each match 0-100 based on semantic alignment. "
      + "Classify the mapping type: direct, partial, indirect, or none.";

  public static final String SCORE_GAP = "You are a risk and compliance analyst. For the given obligation with no or insufficient control coverage, "
      + "score the compliance gap across four legacy dimensions: regulatory urgency (0-1), penalty severity (0-1), "
      + "probability (0-1), and business impact (0-1). "
      + "Also score five residual-risk dimensions, each as a float 0.0-1.0: "
      + "severity (impact if unaddressed; 0=trivial, 1=existential), "
      + "likelihood (probability of occurrence; 0=unlikely, 1=certain), "
      + "detectability (how hard to detect; 0=obvious, 1=silent failure — higher means harder to detect = higher risk), "
      + "blast_radius (breadth of impact; 0=one user, 1=whole org), "
      + "recoverability (cost to recover; 0=trivial rollback, 1=unrecoverable). "
      + "Provide recommended remediation actions and a narrative.\n"
      + "\n"
      + "### Calibration anchors (reference scale, do not copy verbatim)\n"
      + "\n"
      + "For each axis, here are the 0.2 / 0.5 / 0.9 reference points:\n"
      + "\n"
      + "severity:\n"
      + "- 0.2 — minor process gap (e.g., late submission of a routine quarterly ICAAP attestation; remediated by a reminder)\n"
      + "- 0.5 — material control weakness (e.g., AML transaction monitoring ruleset covers 95% of typologies but misses structuring edge cases)\n"
      + "- 0.9 — systemic breach (e.g., sanctions screening offline for 24 h; high probability of prohibited transactions clearing)\n"
      + "\n"
      + "likelihood:\n"
      + "- 0.2 — remote trigger condition (e.g., MiCA whitepaper omission affects only non-asset-referenced tokens not yet issued)\n"
      + "- 0.5 — plausible under normal operations (e.g., GDPR data-subject request SLA breach during peak onboarding periods)\n"
      + "- 0.9 — near-certain given current state (e.g., KYC enhanced due-diligence step skipped for PEP segment with no compensating control)\n"
      + "\n"
      + "detectability:\n"
      + "- 0.2 — failure surfaces immediately (e.g., capital adequacy ratio breach triggers automated regulatory reporting alert)\n"
      + "- 0.5 — detectable within days via periodic review (e.g., missing SAR filing identified in next monthly compliance sample audit)\n"
      + "- 0.9 — silent failure; unlikely to surface without external trigger (e.g., correspondent-bank SWIFT screening gap undetected until de-risking notice)\n"
      + "\n"
      + "blast_radius:\n"
      + "- 0.2 — single product line or narrow customer segment (e.g., savings account interest-disclosure gap affecting one jurisdiction)\n"
      + "- 0.5 — significant customer population or multiple business lines (e.g., cookie-consent defect affecting all EU web users)\n"
      + "- 0.9 — institution-wide or systemic (e.g., group-level AML policy gap exposing all subsidiaries to regulator action)\n"
      + "\n"
      + "recoverability:\n"
      + "- 0.2 — trivial rollback (e.g., incorrect regulatory report version resubmitted within the correction window at no penalty)\n"
      + "- 0.5 — recoverable with significant effort (e.g., GDPR breach notification sent late; ICO fine and remediation plan required)\n"
      + "- 0.9 — largely unrecoverable (e.g., correspondent-bank relationship terminated after AML enforcement action; reputational harm permanent)\n"
      + "\n"
      + "Use these anchors as your scale; never quote them as evidence.";

  private OldPrompts() {
  }
}
