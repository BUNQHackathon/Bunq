# bunq Internal Control — KYC Onboarding

---

## Document Control

| Field | Value |
|---|---|
| Document ID | CTRL-KYC-003 |
| Version | 1.0 |
| Owner | Head of Identity & Onboarding |
| Approved By | Chief Compliance Officer |
| Last Reviewed | 2026-Q1 |
| Classification | Internal — Restricted |
| Next Review Due | 2027-Q1 |

---

## 1. Purpose

This procedure defines the Know Your Customer (KYC) onboarding process for all new bunq customers, covering identity verification, customer due diligence (CDD), beneficial ownership determination, and initial risk classification. The procedure ensures that bunq establishes a verified customer identity and an appropriate risk profile before activating any product or service. It operationalises the requirements of the Wwft, EU Regulation 2024/1624 (AMLR), and GDPR as they apply to the onboarding lifecycle, and feeds directly into the ongoing monitoring and CDD review processes governed by CTRL-AML-001.

---

## 2. Scope

**In scope:**
- All individual (retail) customers applying for a bunq account via the mobile app or API.
- All business customers (sole traders, SMEs, foundations, and other legal entities) applying for a bunq business account.
- Re-onboarding of existing customers where material identity information has changed or where a lapsed account is reactivated after more than 24 months of inactivity.

**Out of scope:**
- Corporate customers undergoing the institutional onboarding process (governed by CTRL-KYC-005).
- Employee accounts (governed by HR-003).
- Customers onboarded exclusively through a regulated partner under a contractual CDD delegation agreement, provided bunq has verified the partner's CDD standards in its annual vendor assessment.

---

## 3. Roles and Responsibilities

| Role | Responsibility |
|---|---|
| **Onboarding Compliance Analyst** | Reviews automated flags on onboarding sessions; manually reviews edge cases; escalates to MLRO where required. |
| **Head of Identity & Onboarding** | Owns the onboarding technology stack configuration, ID vendor SLAs, and acceptance rate quality metrics. |
| **MLRO / Deputy MLRO** | Reviews and approves all EDD cases before account activation; receives PEP escalations. |
| **Data Protection Officer (DPO)** | Advises on lawful basis for identity data processing; reviews and approves any new identity data field collection. |
| **Financial Crime Operations** | Performs adverse media screening and manual research for complex legal entity structures. |

---

## 4. Procedure

### 4.1 Initial Applicant Data Capture

1. The applicant provides: full legal name, date of birth, nationality, country of residence, and mobile phone number via the bunq app. For business accounts, the applicant additionally provides: registered company name, registration number, jurisdiction of incorporation, and registered address.
2. A real-time device and email/phone ownership check is performed (one-time passcode, device fingerprint) to bind the application to a verifiable contact point and reduce synthetic identity risk.
3. The data fields collected are the minimum necessary for identity verification and CDD purposes, in line with GDPR Art. 5(1)(c) (data minimisation). The DPO has approved the data collection schema as of the last annual review.

### 4.2 Identity Document Capture and Liveness Check

4. The applicant is prompted to capture a government-issued identity document (national ID card or passport; driving licences accepted for NL residents where permitted by Wwft). The document is captured via the in-app camera with NFC chip reading where the document supports it.
5. The identity verification provider (integrated via API) performs: (a) document authenticity checks (MRZ validation, security feature analysis, chip data comparison for NFC-capable documents); (b) biometric liveness check (passive or active liveness, vendor-dependent) to confirm the person holding the document is present and not a spoofed image; (c) face-match comparison between the liveness capture and the document photo.
6. The provider returns a structured decision: PASS, REFER, or FAIL, with sub-scores per check type.
7. FAIL outcomes automatically reject the onboarding session; the applicant may retry once within 24 hours. A second FAIL places the application in manual review.
8. REFER outcomes are queued for manual review by an Onboarding Compliance Analyst within 4 business hours.
9. Only PASS outcomes (or manual approvals after REFER review) allow progression to the next step.

### 4.3 Address Verification

10. For customers resident in the Netherlands: the applicant's BSN (Burgerservicenummer) is verified against the BRP (Basisregistratie Personen) via the authorised government data access channel, confirming address and liveness of registration. The BSN is not stored beyond the verification transaction; only the verification outcome and timestamp are retained, in line with the Dutch General Data Protection Act (*AVG*) restrictions on BSN processing.
11. For customers resident in other EU/EEA countries: address is verified by one of the following: (a) utility bill or bank statement dated within 90 days, submitted via the app document upload; (b) credit bureau address confirmation via API where available in the jurisdiction; or (c) SEPA bank account ownership check (micro-deposit or instant account verification).
12. For customers resident outside the EU/EEA: address verification requirements are elevated to Tier 3 EDD by default, per the CDD tier framework in CTRL-AML-001.

### 4.4 PEP and Adverse Media Screening

13. Upon completion of identity verification, the customer's name, date of birth, and nationality are submitted to the third-party screening provider for PEP and adverse media screening (simultaneous with sanctions screening, per CTRL-SANC-002).
14. A PEP match (any tier: domestic PEP, foreign PEP, international organisation PEP, or family member/close associate of a PEP per AMLR Art. 2(1)(24)) triggers automatic elevation to Tier 3 EDD. The MLRO or Deputy MLRO must approve account activation.
15. An adverse media hit (negative news linked to financial crime, fraud, corruption, or terrorism) requires manual review by an Onboarding Compliance Analyst. Depending on the severity and credibility of the source, the analyst may clear the hit with documented rationale or escalate to the MLRO for an EDD determination.
16. All PEP screening outcomes, whether positive or negative, are recorded in the customer's KYC record.

### 4.5 Beneficial Ownership — Legal Entities

17. For all legal entity applicants, the Onboarding Compliance Analyst identifies the Ultimate Beneficial Owner(s) (UBOs) — natural persons who own or control more than **25%** of the entity's shares, voting rights, or other ownership interests, per AMLR Art. 2(1)(4) and the UBO register requirements under AMLD6.
18. UBO information is obtained from: (a) the national UBO register (KVK UBO register for NL entities, equivalent for other EU jurisdictions); (b) the applicant's self-declaration and supporting documentation (shareholder register, constitutional documents); (c) third-party registry data where available.
19. Each identified UBO undergoes the full individual KYC process in 4.2 and 4.4 above (ID verification, liveness, PEP/sanctions screening).
20. Where the UBO register data and self-declaration are inconsistent, or where beneficial ownership cannot be confirmed due to complex layered structures, the account is automatically elevated to Tier 3 EDD and the MLRO is notified.
21. For entities where no natural person exceeds the 25% threshold (e.g., widely held companies), the senior managing official(s) are identified and screened as the control persons, per AMLR Art. 22.

### 4.6 Risk Scoring and Tier Assignment

22. Upon completion of all verification steps, the onboarding system automatically calculates the customer's initial risk score using a weighted combination of: customer type (individual/entity), nationality and country of residence risk rating, product type and expected transaction volume (declared at onboarding), PEP status, adverse media outcome, and UBO complexity (for legal entities).
23. The risk score maps to a CDD tier (Tier 1, 2, or 3) as defined in CTRL-AML-001, Section 4.2. The tier and score are stored immutably in the customer risk profile at the time of onboarding.
24. Tier 3 assignments require MLRO or Deputy MLRO written approval before account activation. Approval is logged in the case management system with the approver's user ID and timestamp.

### 4.7 Account Activation Decision

25. The system produces an onboarding decision: APPROVE, DECLINE, or PENDING (for manual review cases). Accounts are activated only after all of the following conditions are met: identity verification PASS, address verification complete, PEP/sanctions screening clear (or EDD approval obtained), risk tier assigned.
26. Declined applicants receive a notification via the app; the specific reason is not disclosed where tipping-off risk applies.
27. The onboarding Compliance Analyst documents the rationale for any manual APPROVE or DECLINE decision in the case management system.

---

## 5. Frequency and Triggers

| Activity | Frequency / Trigger |
|---|---|
| Full KYC onboarding | Each new account application |
| Re-KYC (existing customers) | Account dormancy > 24 months; material change in customer information; risk tier upgrade; regulatory re-verification requirement |
| Adverse media refresh | Triggered by transaction monitoring alert or 12-month periodic review |
| UBO verification refresh | On change of ownership notified by customer; or at annual review for Tier 3 entities |
| Liveness check re-run | Where ID document has expired and renewal is submitted |

---

## 6. Records and Retention

- All KYC records collected during onboarding (document images, verification outcomes, liveness metadata, risk scores, tier assignment, approval records) are retained for **5 years after account closure**, per Wwft Art. 33 and AMLR Art. 56.
- BSN verification outcomes for NL residents are retained as a boolean outcome only (verified: yes/no, date, channel); raw BSN data is not stored beyond the verification API call.
- EDD case records (PEP approvals, MLRO decision notes) are retained for **5 years** after the relevant decision.
- Records are stored in encrypted object storage with access restricted to Compliance, Legal, and Financial Crime Operations. DNB and other competent authorities may request access under statutory powers.
- Requests from customers to exercise GDPR right of erasure (Art. 17) are assessed against the Wwft retention obligation, which overrides erasure requests for the mandatory 5-year period, subject to DPO confirmation.

---

## 7. References

| Regulation | Relevance |
|---|---|
| EU Regulation 2024/1624 (AMLR) Arts. 18–26 | Customer due diligence; identification and verification; beneficial ownership |
| Wwft 2018 (NL) Arts. 3–11 | CDD obligations; identity verification standards |
| EU Regulation 2019/1157 (eIDAS 2 / IDAS) | Standards for electronic identity verification across EU |
| GDPR (EU 2016/679) Arts. 5, 6, 9, 17 | Lawful basis for processing; data minimisation; special category data; erasure |
| AVG (Dutch GDPR implementation) | BSN processing restrictions |
| FATF Recommendation 10 | Customer due diligence standards |
| EBA Guidelines on Remote Customer Onboarding (EBA/GL/2022/15) | Video and digital ID verification standards |
