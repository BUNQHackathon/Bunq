# SyntheticBank_Policies -- Northwind Bank N.V. AML Policy Corpus

## What this is

A **fully synthetic** anti-money-laundering ("AML") and counter-terrorist-financing ("CTF") policy corpus, written for a fictional bank, **Northwind Bank N.V.** Northwind is an EU-licensed credit / e-money institution headquartered in the Netherlands, serving retail and SME customers in Italy under freedom-of-services (EU) passporting.

The corpus was engineered so that a known, pre-defined set of Italian AML obligations resolve to one of three outcomes when checked against it: **SATISFIED**, **JURISDICTION_DELTA**, or **CONTROL_MISSING**. That planted design is the ground truth against which a gap-analysis pipeline can be scored -- see `ground_truth.json`.

Northwind's premise: its policy set was drafted to a Dutch/EU baseline and has **not been localised for Italy**, even though it serves Italian customers. This is what makes the planted gaps realistic -- Italy imposes obligations (record retention period, UIF reporting channel and format, the Banca d'Italia residual-risk matrix, S.AR.A. aggregate reporting, etc.) that a Netherlands-drafted policy set would plausibly miss or only partially cover.

## Provenance

This corpus was authored entirely from **public sources**: the FATF Recommendations, the EU Anti-Money Laundering Directives, standard industry AML control taxonomy, and the Italian regulatory texts referenced below. It does not reproduce, reference, or derive from any real institution's internal policies, vendor names, system names, team names, or document structure. No content in this folder was copied from, or informed by, the `Policies/` folder elsewhere in this repository -- that folder was never opened while producing this corpus. All internal system names ("Customer Risk Engine", "Watchlist Screening Service", "Northwind Document Vault", "Northwind Learning Hub"), role titles, and thresholds are invented for this exercise, except where a numeric threshold or article number is drawn directly from the public Italian legislative texts named in each policy statement's regulatory citation (only in `ground_truth.json`, not inside the policy documents themselves).

## Files

### Labelled policy documents (01-10)

These 10 files carry the planted ground truth in `ground_truth.json`. Every `satisfied` and `jurisdiction_delta` entry cites a verbatim quote from one of these files; every `control_missing` entry is, by design, absent from all of them (including the distractors below).

| File | Topic | Control statements |
|---|---|---|
| `01_customer_due_diligence_and_onboarding.md` | CDD / onboarding | 7 |
| `02_enhanced_due_diligence_and_peps.md` | EDD, PEPs, correspondent banking | 7 |
| `03_sanctions_screening_and_asset_freezing.md` | Sanctions screening, asset freezing | 6 |
| `04_transaction_monitoring.md` | Ongoing transaction monitoring | 5 |
| `05_suspicious_activity_reporting.md` | Internal escalation, external SAR/STR filing | 6 |
| `06_record_retention_and_data_management.md` | Record retention, data storage | 5 |
| `07_ML_TF_risk_assessment.md` | Enterprise & customer risk assessment | 4 |
| `08_aml_governance_and_internal_controls.md` | AML governance, MLRO, audit, whistleblowing | 6 |
| `09_training_and_awareness.md` | AML/CTF training | 5 |
| `10_outsourcing_and_third_party_risk.md` | Outsourcing / agent oversight | 5 |
| **Subtotal (labelled)** | | **56** |

### Distractor documents (11-26, unlabelled)

These 16 files are **not referenced anywhere in `ground_truth.json`**. They exist to restore a realistic retrieval haystack: with only the 10 labelled files (56 controls), a top-20 candidate shortlist would cover roughly a third of the entire corpus, which flatters retrieval accuracy far beyond what a real bank's policy library (hundreds of controls) would produce. The distractors are semantically adjacent (fraud, disputes, complaints, conduct, credit, tax, data protection) or operationally adjacent (security, change management, resilience, HR) to the labelled AML/CTF material, so they compete for retrieval slots without ever supplying a planted answer -- none of them satisfy, partially satisfy, or otherwise touch any of the 27 obligations in `ground_truth.json`. See "Distractor integrity" below for how this was checked.

| File | Topic | Control statements |
|---|---|---|
| `11_payment_fraud_and_scam_prevention.md` | Payment fraud & scam prevention | 10 |
| `12_card_dispute_and_chargeback_handling.md` | Card disputes, chargebacks | 9 |
| `13_account_takeover_and_authentication_controls.md` | Account takeover, customer authentication | 9 |
| `14_internal_fraud_and_whistleblowing.md` | Internal fraud, staff whistleblowing | 9 |
| `15_customer_complaints_handling.md` | Customer complaints handling | 9 |
| `16_conduct_risk_and_fair_treatment_of_customers.md` | Conduct risk, fair treatment of customers | 9 |
| `17_credit_risk_and_affordability_assessment.md` | Credit risk, affordability assessment | 9 |
| `18_collections_and_arrears_management.md` | Collections, arrears management | 9 |
| `19_account_closure_and_dormancy.md` | Account closure, dormancy | 9 |
| `20_tax_reporting_crs_fatca.md` | Tax reporting (CRS / FATCA) | 9 |
| `21_consumer_data_protection_and_gdpr_operations.md` | Consumer data protection / GDPR operations | 9 |
| `22_information_security_and_access_management.md` | Information security, access management | 8 |
| `23_it_change_and_release_management.md` | IT change and release management | 7 |
| `24_business_continuity_and_operational_resilience.md` | Business continuity, operational resilience | 7 |
| `25_incident_management.md` | Incident management | 7 |
| `26_hr_onboarding_and_background_checks.md` | HR onboarding, background checks | 7 |
| **Subtotal (distractors)** | | **136** |

### Corpus totals

| | Files | Control statements |
|---|---|---|
| Labelled (01-10) | 10 | 56 |
| Distractors (11-26) | 16 | 136 |
| **Total corpus** | **26** | **192** |

Each document -- labelled or distractor -- follows a consistent structure: title, version, owner role, effective date, scope, purpose, and numbered policy statements containing concrete, extractable control statements ("The ... must ..."). All documents read as part of the same normal, internally consistent Northwind Bank N.V. policy library (shared role titles, shared internal system names such as the Customer Risk Engine and Northwind Document Vault, consistent effective date) -- no planted gap, and no distractor status, is signposted inside the documents themselves.

### Distractor integrity

The 16 distractor documents were checked to confirm they introduce no new ground-truth coverage:

- **Forbidden-term scan:** grepped (case-insensitive) for every term tied to a `control_missing` or `jurisdiction_delta` obligation -- UIF, S.AR.A./SARA, Banca d'Italia, proliferation financing/WMD, OAM, Financial Security Committee, "segnalazioni", goAML, and any "ten years"/"10 years" retention period. **Zero hits** across all 16 files.
- **Contamination review (control_missing items, GT-22 through GT-27):** confirmed one-by-one that no distractor supplies the missing control -- S.AR.A. aggregate reporting to UIF, the Banca d'Italia annual self-assessment, proliferation-financing risk assessment, OAM payment-agent registration, control-body reporting to an external Italian authority, and UIF anomaly-indicator-driven monitoring scenarios remain entirely unaddressed anywhere in the corpus, including the new files.
- Retention periods in the new files use six years, "duration of employment plus one year", three years, and similar generic values -- never ten years -- so none of them accidentally supply the Italian 10-year retention parameter (GT-13).

## Ground truth: `ground_truth.json`

An array of 27 target obligations drawn from the four in-scope Italian regulatory sources:

- D.Lgs 231/2007 (core AML: CDD, retention, STR duty, internal controls, S.AR.A.)
- D.Lgs 109/2007 (CTF asset freezing)
- UIF Instructions 18 December 2025 (STR/SOS detection and reporting)
- UIF Provision 25 August 2020 (S.AR.A. aggregate reporting)

with supporting context from the transcribed Banca d'Italia residual-risk matrix (`04d_BoI-residual-risk-matrix_transcription.md`).

### Status distribution

| Expected status | Count | Meaning |
|---|---|---|
| `satisfied` | 12 | A control in the corpus directly and fully matches the obligation -- no Italy-specific gap. |
| `jurisdiction_delta` | 9 | A generic version of the control exists, but the Italy-specific parameter, channel, or format is absent or wrong. |
| `control_missing` | 6 | Nothing in the corpus addresses the obligation at all. |
| **Total** | **27** | |

### Schema

Each entry:

```json
{
  "id": "GT-01",
  "obligation_summary": "<one sentence, plain English>",
  "regulation": "<e.g. D.Lgs 231/2007 Art. 31>",
  "source_file": "<which regulation PDF it came from>",
  "expected_status": "satisfied | jurisdiction_delta | control_missing",
  "planted_control_doc": "<policy .md filename, or null when control_missing>",
  "planted_control_quote": "<verbatim sentence from that policy file, or null>",
  "rationale": "<why this status -- for deltas, states exactly what Italian-specific element is missing>"
}
```

For every entry with a non-null `planted_control_quote`, that string is a **byte-for-byte substring** of the named `planted_control_doc` file. This was verified programmatically (Node.js `String.includes()` check over all 21 non-null quotes) with **zero failures** -- see the verification note at the end of this file.

### Example items (one per status)

**Satisfied -- GT-03** (PEP approval): the obligation requires senior-management sign-off before a PEP relationship is opened or continued (D.Lgs 231/2007 Art. 25(4)(a)); Northwind's EDD policy states: *"A member of Northwind's senior management must approve, in writing, the establishment or continuation of any business relationship with a customer, beneficial owner, or beneficiary identified as a politically exposed person before the relationship is opened or continued."* -- a direct match.

**Jurisdiction delta -- GT-13** (record retention): Italian law requires 10-year retention (D.Lgs 231/2007 Art. 31(3)); Northwind's retention policy states: *"Customer due diligence records, transaction records, and internal SAR referrals must be retained for a period of five years from the end of the business relationship or the date of the occasional transaction."* -- a generic control exists, but at half the mandated Italian period.

**Jurisdiction delta -- GT-14** (STR channel): Italian law requires suspicious transaction reports to go to UIF in SOS format; Northwind's SAR policy states: *"Where the MLRO determines that a suspicious activity report is warranted, the MLRO must file the report with the Financial Intelligence Unit-Netherlands (FIU-Nederland) through its goAML portal."* -- the reporting control exists but is routed entirely to the wrong (Dutch) authority.

**Control missing -- GT-22** (S.AR.A. aggregate reporting): the Italy-specific duty to periodically transmit aggregate AML data to UIF (D.Lgs 231/2007 Art. 33; UIF Provision 25 August 2020) has no corresponding control anywhere in the corpus -- Northwind's only regulatory-reporting control is case-by-case SAR filing to the Dutch FIU.

## How to use this to score a pipeline run

1. Point the gap-analysis pipeline at `SyntheticBank_Policies/*.md` as the policy corpus and at the four named Italian regulatory PDFs (or a subset covering the same obligations) as the regulatory source. This glob deliberately includes the 16 distractor files (11-26) alongside the 10 labelled files -- retrieval is meant to be scored against the full 192-control haystack, not just the labelled subset.
2. For each of the 27 obligations in `ground_truth.json`, compare the pipeline's classification of that obligation (or the nearest obligation it extracted covering the same regulatory citation) against `expected_status`.
3. Score:
   - **True positive (satisfied)**: pipeline says satisfied and cites a control whose text overlaps `planted_control_quote`.
   - **True positive (jurisdiction_delta)**: pipeline flags a delta and its stated gap matches the `rationale` (i.e. it identifies the same missing Italian-specific parameter).
   - **True positive (control_missing)**: pipeline reports no matching control found.
   - Any mismatch (e.g. pipeline calls a planted delta "satisfied", or calls a planted "control_missing" obligation "satisfied" by hallucinating a control) is a scoring miss -- these are exactly the pipeline failure modes this corpus is designed to surface.
4. Aggregate precision/recall per status to evaluate whether the pipeline reliably distinguishes "control exists and matches," "control exists but misses the Italian specifics," and "no control at all."

## Verbatim-quote verification (performed)

A Node.js script loaded `ground_truth.json`, and for every entry with a non-null `planted_control_quote`, confirmed the quote string is an exact substring of the file named in `planted_control_doc`.

- Entries checked: 21 (all `satisfied` and `jurisdiction_delta` entries; `control_missing` entries carry `null` by design)
- Failures: **0**
- Status distribution confirmed: 12 satisfied / 9 jurisdiction_delta / 6 control_missing (27 total)
