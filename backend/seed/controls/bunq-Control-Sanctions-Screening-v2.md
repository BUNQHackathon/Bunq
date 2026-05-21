# bunq Internal Control — Sanctions Screening

---

## Document Control

| Field | Value |
|---|---|
| Document ID | CTRL-SANC-002 |
| Version | 2.0 |
| Owner | Chief Compliance Officer |
| Approved By | Management Board |
| Last Reviewed | 2026-Q1 |
| Classification | Internal — Restricted |
| Next Review Due | 2027-Q1 |

---

## 1. Purpose

This Standard Operating Procedure defines bunq's process for screening customers, counterparties, and transactions against applicable sanctions lists, including those published by the European Union, United Nations Security Council, UK Office of Financial Sanctions Implementation (OFSI), and the US Office of Foreign Assets Control (OFAC). The objective is to ensure that bunq does not facilitate financial transactions or relationships with sanctioned individuals, entities, or jurisdictions, thereby meeting its obligations under EU Regulation 2580/2001, Council Regulation (EU) 833/2014 (as amended), and the Dutch Sanctions Act (*Sanctiewet 1977*).

---

## 2. Scope

**In scope:**
- All natural and legal persons onboarding as bunq customers.
- All transaction beneficiaries and originators processed through bunq payment rails.
- Periodic rescreening of the existing customer base upon list updates.
- Correspondent banking relationships and third-party payment service provider connections.

**Out of scope:**
- Internal employee screening (governed by HR Policy HR-003).
- Vendor and supplier due diligence (governed by Procurement Policy PROC-001).
- Indirect SWIFT correspondent chains not visible to bunq in the payment message.

---

## 3. Roles and Responsibilities

| Role | Responsibility |
|---|---|
| **Compliance Officer (Sanctions)** | Owns screening configuration, list subscriptions, threshold management, and monthly quality review. |
| **Money Laundering Reporting Officer (MLRO)** | Final decision authority on confirmed matches; escalation point for complex hits; reports to DNB on list mismatches as required. |
| **Screening Analyst (Level 1)** | First-line review of system-generated alerts; disposes of clear false positives within 24 hours. |
| **Screening Analyst (Level 2 — 4-eyes)** | Independent review of any match the Level 1 analyst has not cleared; required for any potential true match. |
| **Head of Financial Crime Operations** | Manages analyst capacity and SLA adherence; escalates resource gaps to CCO. |

---

## 4. Procedure

### 4.1 Real-Time Screening at Onboarding

1. During account application, the bunq onboarding API submits the applicant's full legal name, date of birth, nationality, and country of residence to the third-party screening provider (ComplyAdvantage or equivalent Refinitiv World-Check-class service) via a synchronous API call before any account is activated.
2. The screening provider returns a structured match list with a fuzzy-match score and source list reference (EU Consolidated, UN, OFAC SDN, OFSI Consolidated, etc.).
3. Any response with a match score at or above the system threshold (default: 85%) blocks account activation and creates a case in the case management system with status *Pending Level 1 Review*.
4. Accounts flagged as potential matches remain suspended; the applicant receives no information about the specific reason for delay beyond a generic "your application is under review" message.

### 4.2 Real-Time Transaction Screening

5. Every outbound and inbound payment instruction is screened against the same sanctions data set at payment initiation, prior to settlement.
6. Name-based screening covers: payer name, payee name, payer IBAN BIC country code, payee IBAN BIC country code, and any free-text remittance information fields.
7. Payments flagging a match at or above threshold are placed in a *Payment Hold* queue; the customer's app displays "payment is being processed" without disclosing the hold reason.
8. Payments in hold status must be reviewed within 4 business hours during operating hours (08:00–20:00 CET) or escalated to on-call MLRO outside those hours.

### 4.3 Alert Triage — Level 1 Review

9. The Level 1 Screening Analyst reviews all open alerts in the case management queue at least every 2 hours during business hours.
10. For each alert, the analyst compares the matched fields against the underlying sanctions list entry, using all available identifying information (DOB, nationality, address, entity type, SWIFT BIC).
11. If the analyst determines the match is a false positive with documented reasoning, they clear the case with a written disposition note and the account/payment is released.
12. If the analyst cannot conclusively clear the match, or if the match score exceeds 95% on two or more fields, the case is escalated to Level 2 review.

### 4.4 Alert Triage — Level 2 (4-Eyes) Review

13. A second, independent Level 2 analyst reviews the case without access to the Level 1 analyst's disposition note until they have recorded their own independent finding.
14. If both analysts independently conclude the match is a false positive, the case is cleared. The disposition is logged with both analysts' IDs and timestamps.
15. If either analyst concludes the match is a potential true hit, the case is immediately escalated to the MLRO.

### 4.5 MLRO Escalation and Reporting

16. The MLRO reviews true-hit escalations within 2 business hours. The MLRO may request additional documentation from the customer (via a generic information request) to resolve the match.
17. If the MLRO confirms a true match, bunq immediately: (a) freezes the account and/or rejects the payment; (b) does not notify the customer of the specific reason ("tipping off" prohibition, Sanctions Act Art. 9a); (c) reports to the Financial Intelligence Unit Netherlands (FIU-NL) via the Unusual Transactions portal and, where required by EU sanctions regulations, to DNB within 24 hours.
18. The MLRO documents the decision in the case management system with a narrative rationale and time-stamps.

### 4.6 Batch Rescreening

19. The full active customer base is rescreened overnight (00:00–05:00 CET) every calendar day against the latest published versions of all subscribed sanctions lists.
20. The screening provider delivers intraday list update notifications; emergency rescreening is triggered automatically within 1 hour of any emergency designation by the EU Council or OFAC.
21. Overnight batch results are reviewed by the Compliance Officer (Sanctions) each morning by 09:00 CET; any new hits follow the triage process in 4.3–4.5.

### 4.7 List Subscription Management

22. The Compliance Officer (Sanctions) verifies monthly that all required list subscriptions are current: EU Consolidated Sanctions List, UN Consolidated List, OFAC SDN & Non-SDN lists, OFSI Consolidated List, Dutch national designations.
23. Any gap in subscription coverage is escalated to the CCO within 1 business day and remediated within 5 business days.

---

## 5. Frequency and Triggers

| Activity | Frequency / Trigger |
|---|---|
| Customer onboarding screen | Each new account application, synchronous |
| Transaction screen | Each payment initiation, synchronous |
| Overnight batch rescreen | Daily, 00:00 CET |
| Emergency rescreen | Within 1 hour of new designation |
| List subscription review | Monthly |
| Threshold and tuning review | Quarterly |
| Full procedure review | Annually, or upon material regulatory change |

---

## 6. Records and Retention

- All screening results (match or no-match) are logged in the case management system with timestamp, applicant/customer ID, match score, list name, and analyst disposition.
- Case records for **true hits and confirmed matches** are retained for **5 years** from the date of the decision, per Wwft Art. 33 and AMLR Art. 56.
- Bulk no-match logs (aggregated screening run results) are retained for **5 years**.
- Emergency escalation records and MLRO decisions are retained for **7 years** from the date of the relevant event.
- Records are stored in encrypted form in bunq's compliance data store; access is restricted to the Compliance and Legal functions and DNB upon request.

---

## 7. References

| Regulation | Relevance |
|---|---|
| EU Regulation 2024/1624 (AMLR) | Arts. 14–17: sanctions screening obligations for obliged entities |
| Sanctiewet 1977 (NL) | Dutch national sanctions framework, tipping-off prohibition |
| EU Consolidated Sanctions List Regulation 269/2014 et al. | Specific EU restrictive measures programmes |
| FATF Recommendation 6 | Targeted financial sanctions related to terrorism and proliferation |
| DNB Good Practice Sanctions 2022 | Supervisory expectation on screening controls |
| Wwft 2018 (NL) | Customer due diligence and record-keeping |
