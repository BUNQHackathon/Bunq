# bunq Internal Control — AML/CFT Framework

---

## Document Control

| Field | Value |
|---|---|
| Document ID | CTRL-AML-001 |
| Version | 1.0 |
| Owner | Money Laundering Reporting Officer (MLRO) |
| Approved By | Management Board / Supervisory Board |
| Last Reviewed | 2026-Q1 |
| Classification | Internal — Restricted |
| Next Review Due | 2027-Q1 |

---

## 1. Purpose

This document establishes bunq's enterprise-wide framework for preventing, detecting, and reporting money laundering and the financing of terrorism (AML/CFT). It sets out the governance structure, risk assessment methodology, customer due diligence (CDD) tiers, transaction monitoring approach, and suspicious transaction reporting (STR) obligations. The framework is designed to meet bunq's obligations as a licensed credit institution under the Dutch Central Bank (DNB) and to comply with the Wet ter voorkoming van witwassen en financieren van terrorisme (*Wwft*), EU Regulation 2024/1624 (AMLR), and the FATF Recommendations. This control is the apex document from which all operational AML/CFT procedures derive.

---

## 2. Scope

**In scope:**
- All bunq legal entities holding payment institution or banking licenses.
- All customer segments: retail, business (SME and freelancer), and marketplace partners.
- All products and channels offered by bunq, including the mobile app, API-based access, and card programs.
- All staff with customer-facing, operations, product, and technology roles in so far as they handle or influence customer data or transactions.

**Out of scope:**
- bunq's non-regulated group entities (e.g., marketing subsidiaries) that do not process customer funds.
- Correspondent relationships managed entirely by a licensed third-party processor under a contractual AML delegation agreement (subject to annual review).

---

## 3. Roles and Responsibilities

| Role | Responsibility |
|---|---|
| **Management Board** | Approves EWRA, AML/CFT policy suite, and material risk appetite statements annually; accountable to DNB for the framework. |
| **MLRO** | Day-to-day ownership of the AML/CFT programme; receives internal suspicion reports; files STRs to FIU-NL; advises the Board. |
| **Deputy MLRO** | Covers MLRO duties during absence; manages Level 2 case reviews and analyst team. |
| **Compliance Officer (Financial Crime)** | Maintains policies and training programmes; conducts thematic reviews; manages third-party screening vendor relationships. |
| **First-Line Risk Owners (Product / Operations)** | Implement controls within their domain; report control failures to Compliance within 24 hours of detection. |

---

## 4. Procedure

### 4.1 Enterprise-Wide Risk Assessment (EWRA)

1. The MLRO coordinates an annual Enterprise-Wide Risk Assessment covering: (a) inherent ML/TF risk by customer segment, product, channel, and geography; (b) effectiveness of current controls (control adequacy assessment); (c) residual risk ratings per risk domain (Low, Medium, High, Very High).
2. The EWRA draws on: internal transaction data and SAR trends from the prior 12 months; DNB and EBA typology reports; FATF Mutual Evaluation findings relevant to NL; FIU-NL annual disclosures; and bunq's own fraud and complaints data.
3. Draft EWRA is presented to the Supervisory Board Risk Committee for challenge before final approval by the full Management Board.
4. The approved EWRA is submitted to DNB upon request and used to calibrate CDD tiers, transaction monitoring thresholds, and training priorities for the following year.
5. An interim EWRA update is triggered if: bunq launches a materially new product or customer segment; a new geographic market is entered; a regulatory change materially alters the risk landscape; or a major AML incident occurs.

### 4.2 Risk-Based CDD Tiers

6. Each customer is assigned one of three CDD tiers at onboarding, based on risk scoring across: customer type (individual vs. legal entity), country of nationality/residence, product used, expected transaction volumes, source-of-funds plausibility, and PEP/adverse media status.

   - **Tier 1 — Simplified CDD (Low Risk):** Applied where objective low-risk factors are documented (e.g., EU/EEA resident, salary account, low transaction volumes, no PEP indicators). Verification: ID document + liveness check. No enhanced document collection required. Ongoing monitoring: standard automated rules.
   - **Tier 2 — Standard CDD:** Default tier for customers not meeting Tier 1 criteria and not triggering Tier 3 factors. Verification: ID document + liveness check + address verification. Ongoing monitoring: standard rule set with periodic review trigger at 12 months.
   - **Tier 3 — Enhanced Due Diligence (EDD):** Mandatory for: PEPs and their family members/close associates; customers from FATF high-risk or monitored jurisdictions; legal entities where beneficial ownership cannot be confirmed through registry data; customers with complex structures or unusual transaction patterns at onboarding; and any customer manually escalated by a Screening Analyst. EDD requires: source of wealth/funds declaration + corroborating documentation; senior management approval (Deputy MLRO or above) before account activation; and enhanced ongoing monitoring with 6-month periodic review.

7. Tier assignment is recorded in the customer risk profile and reviewed: (a) upon a triggering event (e.g., transaction anomaly, new adverse media); (b) at the scheduled periodic review date; (c) annually for all Tier 3 customers.

### 4.3 Transaction Monitoring

8. Automated transaction monitoring rules are applied to all customer accounts in real time and via nightly batch processes. Rules are calibrated to detect: structuring and smurfing patterns; rapid round-trip transactions; cash-equivalent high-frequency low-value payments; payments to/from high-risk jurisdictions; and deviations from a customer's established behavioral baseline.
9. The monitoring rule library is reviewed and updated quarterly by the Compliance Officer in conjunction with the Financial Crime Operations team. Rule changes are documented in the monitoring system change log and approved by the MLRO.
10. Transaction monitoring alerts are triaged by Financial Crime Operations analysts according to the triage procedure in CTRL-TM-003. Unresolved alerts not escalated within 5 business days are automatically flagged to the MLRO.

### 4.4 Suspicious Transaction Reporting (STR) to FIU-NL

11. Any bunq employee who suspects or has reasonable grounds to suspect that a transaction or attempted transaction involves ML/TF must submit an Internal Suspicion Report (ISR) to the MLRO via the designated internal reporting channel within 24 hours of the suspicion arising.
12. The MLRO reviews each ISR and decides within 5 business days whether to file an Unusual Transaction Report (UTR) with FIU-NL via the Wwft online portal (goAML).
13. Where the suspicion relates to an imminent or ongoing transaction, the MLRO must make a filing decision within 4 business hours, not 5 business days.
14. **Regulatory deadline:** UTRs must be filed with FIU-NL **within 14 days** of the transaction being identified as unusual, per Wwft Art. 16. The MLRO documents the filing date and FIU-NL reference number in the case management system.
15. Post-filing, the MLRO monitors the case for any FIU-NL feedback or law enforcement request. Customer accounts subject to a filed UTR are placed under enhanced monitoring until the MLRO decides otherwise.
16. The tipping-off prohibition (Wwft Art. 23) is strictly observed: no employee may disclose to the customer or any third party that a UTR has been or may be filed.

### 4.5 Annual AML Training

17. All employees in scope (see Section 2) complete mandatory AML/CFT awareness training annually. Employees in high-risk roles (Financial Crime Operations, Compliance, Customer Onboarding) complete role-specific training covering typologies, case studies, and scenario-based assessments.
18. Completion rates are tracked and reported to the MLRO monthly. Any employee with overdue training is flagged to their line manager; access restrictions may be applied after 30 days of non-completion.

---

## 5. Frequency and Triggers

| Activity | Frequency / Trigger |
|---|---|
| EWRA | Annual; and on material change trigger |
| CDD tier review (Tier 3) | Every 6 months |
| CDD tier review (Tier 2) | Every 12 months |
| Transaction monitoring rule review | Quarterly |
| ISR to MLRO | Within 24 hours of suspicion arising |
| UTR filing to FIU-NL | Within 14 days of transaction identification |
| AML training | Annual |
| MLRO report to Board | Quarterly |

---

## 6. Records and Retention

- All EWRA documents, board approvals, and interim updates are retained for **10 years** from date of approval.
- Customer CDD records (including tier assignment, documents collected, and review history) are retained for **5 years after account closure**, per Wwft Art. 33 and AMLR Art. 56.
- ISR and UTR records, including MLRO decision notes and FIU-NL reference numbers, are retained for **5 years** from the date of filing (or from the date of the decision not to file).
- Transaction monitoring alert records and analyst dispositions are retained for **5 years**.
- Training completion records are retained for **5 years** after the employee's contract end date.

---

## 7. References

| Regulation | Relevance |
|---|---|
| EU Regulation 2024/1624 (AMLR) | Enterprise-wide AML risk assessment; CDD obligations; record-keeping |
| Wwft 2018 (NL) | Unusual transaction reporting; customer due diligence; tipping-off prohibition |
| FATF Recommendations 1, 10, 12, 20 | Risk-based approach; CDD; PEPs; suspicious transaction reporting |
| EBA Guidelines on AML/CFT Compliance Officers (EBA/GL/2022/05) | MLRO role and responsibilities |
| EU Directive 2024/1640 (AMLD6) | Supervisory framework; beneficial ownership registers |
| DNB Good Practice Customer Due Diligence 2021 | DNB supervisory expectations for risk-based CDD |
