// bunq Compliance Portal — Typed Mock Data

export type CountryStatus = 'active' | 'watchlist' | 'restricted' | 'inactive';

export interface PrismDocument {
  id: number;
  category: string;
  title: string;
  updated: string;
  size: string;
  lang: string;
}

export interface Sanction {
  id: number;
  name: string;
  type: string;
  source: string;
  listed: string;
  status: string;
}

export interface CountryDetail {
  name: string;
  license: string;
  regulator: string;
  note: string;
}

export interface SuggestedQuestion extends String {}

export interface SampleAnswerSource {
  title: string;
  date: string;
}

export interface SampleAnswer {
  question: string;
  answer: string;
  sources: SampleAnswerSource[];
}

export const documents: PrismDocument[] = [
  { id: 1, category: 'Terms & Conditions', title: 'General Terms & Conditions v4.2', updated: 'Jan 2026', size: '1.2 MB', lang: 'EN' },
  { id: 2, category: 'Pricing', title: 'Personal Account Fee Schedule Q1 2026', updated: 'Jan 2026', size: '340 KB', lang: 'EN/NL' },
  { id: 3, category: 'Privacy', title: 'Privacy Policy & GDPR Statement', updated: 'Dec 2025', size: '890 KB', lang: 'EN' },
  { id: 4, category: 'AML', title: 'AML & KYC Policy Framework 2025', updated: 'Nov 2025', size: '2.1 MB', lang: 'EN' },
  { id: 5, category: 'Licensing', title: 'DNB Banking License Certificate', updated: 'Mar 2024', size: '210 KB', lang: 'NL/EN' },
  { id: 6, category: 'Reports', title: 'Annual Report 2025 — Full Version', updated: 'Apr 2026', size: '8.4 MB', lang: 'EN' },
  { id: 7, category: 'Terms & Conditions', title: 'Business Account Terms v2.1', updated: 'Dec 2025', size: '1.5 MB', lang: 'EN' },
  { id: 8, category: 'Privacy', title: 'Cookie Policy — Updated Apr 2026', updated: 'Feb 2026', size: '180 KB', lang: 'EN/NL' },
  { id: 9, category: 'AML', title: 'Sanctions Screening Procedures Manual', updated: 'Jan 2026', size: '3.2 MB', lang: 'EN' },
];

export const sanctions: Sanction[] = [
  { id: 1, name: 'Vladislav Petrov Kotkov', type: 'Person', source: 'EU', listed: '2022-03-10', status: 'Active' },
  { id: 2, name: 'Meridian Global Trading LLC', type: 'Entity', source: 'OFAC', listed: '2023-07-15', status: 'Active' },
  { id: 3, name: 'MV Arctic Pioneer', type: 'Vessel', source: 'UK', listed: '2022-11-20', status: 'Active' },
  { id: 4, name: 'Consolidated Investments Pyongyang', type: 'Entity', source: 'UN', listed: '2017-09-03', status: 'Active' },
  { id: 5, name: 'Igor Aleksandr Volkov', type: 'Person', source: 'EU', listed: '2023-02-27', status: 'Active' },
  { id: 6, name: 'Teheran Oil Derivatives Corp', type: 'Entity', source: 'OFAC', listed: '2019-05-14', status: 'Under review' },
  { id: 7, name: 'MSC Black Sea Fortune', type: 'Vessel', source: 'UK', listed: '2022-12-01', status: 'Active' },
  { id: 8, name: 'Ahmed Hassan Al-Rashid', type: 'Person', source: 'UN', listed: '2020-04-22', status: 'Active' },
  { id: 9, name: 'Eastern Bridge Holdings BV', type: 'Entity', source: 'NL', listed: '2024-01-08', status: 'Delisted' },
  { id: 10, name: 'Natalia Irina Sorokina', type: 'Person', source: 'EU', listed: '2023-09-15', status: 'Under review' },
];

export const countryStatus: Record<string, CountryStatus> = {
  NLD: 'active', DEU: 'active', FRA: 'active', ESP: 'active', ITA: 'active', BEL: 'active',
  AUT: 'active', PRT: 'active', IRL: 'active', FIN: 'active', GRC: 'active', LUX: 'active',
  MLT: 'active', CYP: 'active', EST: 'active', LVA: 'active', LTU: 'active', SVK: 'active',
  SVN: 'active', HRV: 'active', NOR: 'active', SWE: 'active', DNK: 'active', ISL: 'active',
  LIE: 'active', CHE: 'active', GBR: 'active', POL: 'active', CZE: 'active', HUN: 'active',
  BGR: 'active', ROU: 'active',
  TUR: 'watchlist', ARE: 'watchlist', SGP: 'watchlist', USA: 'watchlist', CAN: 'watchlist',
  AUS: 'watchlist', BRA: 'watchlist', MEX: 'watchlist', ZAF: 'watchlist', IND: 'watchlist',
  IDN: 'watchlist', JPN: 'watchlist', KOR: 'watchlist', SAU: 'watchlist', QAT: 'watchlist',
  RUS: 'restricted', BLR: 'restricted', PRK: 'restricted', IRN: 'restricted', SYR: 'restricted',
  CUB: 'restricted', VEN: 'restricted', SDN: 'restricted', MMR: 'restricted', LBY: 'restricted',
  YEM: 'restricted', SOM: 'restricted', ZWE: 'restricted', AFG: 'restricted',
};

export const countryDetails: Record<string, CountryDetail> = {
  NLD: { name: 'Netherlands', license: 'Full Banking License', regulator: 'De Nederlandsche Bank (DNB)', note: 'Primary jurisdiction, licensed since 2012' },
  DEU: { name: 'Germany', license: 'EU Passport (DNB)', regulator: 'BaFin', note: 'Active operations since 2018' },
  FRA: { name: 'France', license: 'EU Passport (DNB)', regulator: 'ACPR', note: 'Active operations since 2019' },
  GBR: { name: 'United Kingdom', license: 'E-Money Institution', regulator: 'FCA', note: 'Post-Brexit EMI license' },
  ESP: { name: 'Spain', license: 'EU Passport (DNB)', regulator: 'Banco de España', note: 'Active since 2020' },
  ITA: { name: 'Italy', license: 'EU Passport (DNB)', regulator: "Banca d'Italia", note: 'Active since 2020' },
  USA: { name: 'United States', license: 'Expansion Review', regulator: 'FinCEN / OCC', note: 'Market entry under review' },
  RUS: { name: 'Russia', license: 'Restricted', regulator: 'N/A', note: 'Sanctioned jurisdiction — no operations' },
  BLR: { name: 'Belarus', license: 'Restricted', regulator: 'N/A', note: 'Sanctioned jurisdiction — no operations' },
  IRN: { name: 'Iran', license: 'Restricted', regulator: 'N/A', note: 'Sanctioned jurisdiction — no operations' },
  NOR: { name: 'Norway', license: 'EEA Passport (DNB)', regulator: 'Finanstilsynet', note: 'Active EEA operations' },
  SWE: { name: 'Sweden', license: 'EEA Passport (DNB)', regulator: 'Finansinspektionen', note: 'Active EEA operations' },
  DNK: { name: 'Denmark', license: 'EEA Passport (DNB)', regulator: 'Finanstilsynet', note: 'Active EEA operations' },
  CHE: { name: 'Switzerland', license: 'FINMA Authorized', regulator: 'FINMA', note: 'Non-EU active operations' },
  POL: { name: 'Poland', license: 'EU Passport (DNB)', regulator: 'KNF', note: 'Active operations since 2021' },
  BEL: { name: 'Belgium', license: 'EU Passport (DNB)', regulator: 'NBB', note: 'Active operations' },
  AUT: { name: 'Austria', license: 'EU Passport (DNB)', regulator: 'FMA', note: 'Active operations' },
  CHN: { name: 'China', license: 'Not licensed', regulator: 'PBOC', note: 'No current operations' },
  SAU: { name: 'Saudi Arabia', license: 'Expansion Review', regulator: 'SAMA', note: 'Under evaluation' },
};

export const suggestedQuestions: string[] = [
  'Is bunq licensed in Germany?',
  'How are deposits protected?',
  "What's bunq's crypto policy?",
  'Where do I report fraud?',
];

export const sampleAnswer: SampleAnswer = {
  question: 'Is bunq licensed in Germany?',
  answer: 'Yes. bunq operates in Germany through its EU banking passport — derived from the full banking license granted by De Nederlandsche Bank (DNB) in the Netherlands. This single license authorises bunq to offer banking services across all 30 EU/EEA member states. In Germany, BaFin serves as the local supervisory authority, while DNB remains the primary regulator.',
  sources: [
    { title: 'DNB Banking License Certificate', date: 'Mar 2024' },
    { title: 'EU Passport Notification — Germany', date: 'Nov 2023' },
    { title: 'Regulatory Compliance Overview 2025', date: 'Dec 2025' },
  ],
};

export const statusColors: Record<CountryStatus, string> = {
  active: '#FF7819',
  watchlist: '#E8C9A8',
  restricted: '#1C1C1C',
  inactive: '#E8E5E0',
};

export const statusLabels: Record<CountryStatus, string> = {
  active: 'Active',
  watchlist: 'Watchlist',
  restricted: 'Restricted',
  inactive: 'Inactive',
};

// ─── Chats ────────────────────────────────────────────────────────────────

export interface Chat {
  id: string;
  title: string;
  timestamp: string;
  snippet: string;
  citedDocIds?: string[];
}

export const chats: Chat[] = [
  {
    id: 'c1',
    title: 'AML framework — Germany',
    timestamp: 'Just now',
    snippet: 'BaFin requires that the risk-based CDD framework explicitly covers cross-border correspondent relationships — does our current WWFT mapping satisfy this?',
    citedDocIds: ['4', '9'],
  },
  {
    id: 'c2',
    title: 'GDPR retention question',
    timestamp: '2h ago',
    snippet: 'Under Art. 17 GDPR, a customer requested erasure of transaction data. AML policy mandates 7-year retention — how do we document the conflict and lawful basis override?',
    citedDocIds: ['3', '4'],
  },
  {
    id: 'c3',
    title: 'DNB passport scope',
    timestamp: '2h ago',
    snippet: 'Confirmed: the EU passport notification covers payment services and deposit-taking. Investment services under MiFID II require a separate notification to BaFin and ACPR.',
    citedDocIds: ['5'],
  },
  {
    id: 'c4',
    title: 'Sanctions list update Q1',
    timestamp: 'Yesterday',
    snippet: 'EU Regulation 2024/745 added 37 new entities. Screening batch completed — 2 potential matches flagged for enhanced review per section 4 of the Screening Procedures Manual.',
    citedDocIds: ['9', '4'],
  },
  {
    id: 'c5',
    title: 'Cookie policy draft v3',
    timestamp: 'Yesterday',
    snippet: 'Draft v3 aligns cookie categories with IAB TCF 2.2 and adds a legitimate-interest ground for analytics cookies. Legal review pending before publication on bunq.com.',
    citedDocIds: ['8', '3'],
  },
  {
    id: 'c6',
    title: 'Business Terms v2.1 review',
    timestamp: 'Mon',
    snippet: 'Clause 8.3 on liability cap has been aligned with PSD2 Art. 74. UBO verification flow in Schedule B updated to reflect updated FATF guidance on legal-entity customers.',
    citedDocIds: ['7', '4'],
  },
  {
    id: 'c7',
    title: 'FATF recommendations mapping',
    timestamp: 'Mon',
    snippet: 'Completed gap analysis against FATF R.10 (CDD) and R.16 (wire transfers). Two process gaps identified: beneficial owner refresh trigger and PEP re-screening cadence.',
    citedDocIds: ['4', '9'],
  },
  {
    id: 'c8',
    title: 'EMI license post-Brexit',
    timestamp: 'Fri',
    snippet: 'FCA EMI authorisation confirmed under FSMA 2000. Passporting into EEA is no longer available — UK entity must rely on reverse solicitation or third-country regime per jurisdiction.',
    citedDocIds: ['5'],
  },
];

// ─── Folder tree ──────────────────────────────────────────────────────────

export interface FolderNode {
  id: string;
  name: string;
  emoji?: string;
  docIds?: string[];
  children?: FolderNode[];
}

export const folderTree: FolderNode[] = [
  {
    id: 'nl',
    name: 'Netherlands',
    emoji: '🇳🇱',
    children: [
      { id: 'nl-licensing',  name: 'Licensing',          docIds: ['5'] },
      { id: 'nl-aml',        name: 'AML / Sanctions',    docIds: ['4', '9'] },
      { id: 'nl-privacy',    name: 'Privacy',             docIds: ['3', '8'] },
      { id: 'nl-terms',      name: 'Terms & Contracts',  docIds: ['1', '7'] },
    ],
  },
  {
    id: 'de',
    name: 'Germany',
    emoji: '🇩🇪',
    children: [
      { id: 'de-licensing', name: 'Licensing',       docIds: [] },
      { id: 'de-aml',       name: 'AML / Sanctions', docIds: ['4'] },
    ],
  },
  {
    id: 'fr',
    name: 'France',
    emoji: '🇫🇷',
    children: [
      { id: 'fr-licensing', name: 'Licensing',       docIds: [] },
      { id: 'fr-aml',       name: 'AML / Sanctions', docIds: ['4'] },
      { id: 'fr-privacy',   name: 'Privacy',          docIds: ['3'] },
    ],
  },
  {
    id: 'ie',
    name: 'Ireland',
    emoji: '🇮🇪',
    children: [
      { id: 'ie-licensing', name: 'Licensing', docIds: [] },
      { id: 'ie-aml',       name: 'AML',       docIds: ['4'] },
    ],
  },
  {
    id: 'eu',
    name: 'EU-wide',
    emoji: '🇪🇺',
    children: [
      { id: 'eu-reports', name: 'Reports', docIds: ['6'] },
      { id: 'eu-privacy', name: 'Privacy',  docIds: ['3'] },
    ],
  },
  {
    id: 'internal',
    name: 'Internal Memos',
    docIds: ['2'],
    children: [],
  },
  {
    id: 'drafts',
    name: 'Drafts',
    docIds: [],
    children: [],
  },
];

// ─── Document metadata ────────────────────────────────────────────────────

export interface DocumentMeta {
  docId: string;
  isHot?: boolean;
  linkCount: number;
  badge?: 'INTERNAL' | 'NL' | 'DE' | 'FR' | 'EU' | 'INTL';
}

export const documentMetas: DocumentMeta[] = [
  { docId: '1', isHot: false, linkCount: 12, badge: 'INTERNAL' },
  { docId: '2', isHot: false, linkCount: 5,  badge: 'NL' },
  { docId: '3', isHot: true,  linkCount: 18, badge: 'EU' },
  { docId: '4', isHot: true,  linkCount: 17, badge: 'NL' },
  { docId: '5', isHot: false, linkCount: 9,  badge: 'NL' },
  { docId: '6', isHot: false, linkCount: 7,  badge: 'EU' },
  { docId: '7', isHot: false, linkCount: 11, badge: 'INTERNAL' },
  { docId: '8', isHot: true,  linkCount: 6,  badge: 'EU' },
  { docId: '9', isHot: false, linkCount: 14, badge: 'INTL' },
];

// ─── Document content (DocView sections) ─────────────────────────────────

export interface DocSection {
  title: string;
  body: string;
}

export const docContent: Record<string, DocSection[]> = {
  '4': [
    {
      title: '1. Scope & Regulatory Authority',
      body: 'This AML & KYC Policy Framework applies to all customer relationships and transactions processed by bunq B.V., operating under the <cite id="dnb">DNB Banking License</cite>. All procedures are designed to satisfy <cite id="fatf">FATF Recommendations</cite>, AMLD5 as transposed into Dutch law via the <cite id="wwft">WWFT</cite>, and applicable EU sanctions regulations.',
    },
    {
      title: '2. Customer Due Diligence',
      body: 'bunq applies a risk-based, tiered <cite id="kyc">Know Your Customer</cite> process across three levels: simplified CDD for low-risk profiles, standard CDD for general customer relationships, and enhanced CDD for politically exposed persons, high-risk jurisdictions, and complex ownership structures. Enhanced CDD triggers are detailed in the <cite id="sanctions">Sanctions Screening Procedures Manual</cite>.',
    },
    {
      title: '3. Transaction Monitoring & Reporting',
      body: 'Automated transaction monitoring runs continuously against behavioural baselines and typology rules. Suspicious transactions are reported to FIU-NL within the statutory timeframe. Customer data is processed in accordance with the <cite id="privacy">Privacy Policy & GDPR Statement</cite>, and annual compliance performance is disclosed in the <cite id="report">Annual Report</cite>.',
    },
    {
      title: '4. Business Account Obligations',
      body: 'Corporate clients are subject to additional obligations set out in the <cite id="biz">Business Account Terms v2.1</cite>, including ultimate beneficial ownership (UBO) verification per AMLD5 Art. 30, ongoing monitoring of significant changes, and periodic refresh of CDD files. All data handling follows standard <cite id="kyc">KYC</cite> data retention rules.',
    },
    {
      title: '5. Governance & Training',
      body: 'The Chief Compliance Officer is responsible for maintaining this framework. All client-facing staff complete annual AML training accredited against <cite id="fatf">FATF</cite> standards. Policy deviations require documented sign-off by the MLRO and are reported quarterly to the Supervisory Board.',
    },
  ],
  '3': [
    {
      title: '1. Legal Basis for Processing',
      body: 'bunq processes personal data under <cite id="gdpr">GDPR (EU) 2016/679</cite>, primarily on the grounds of contractual necessity (Art. 6.1.b), compliance with a legal obligation (Art. 6.1.c) under the <cite id="dnb">DNB Banking License</cite> conditions, and the Dutch <cite id="wwft">WWFT</cite>. Consent is used only where no other lawful basis applies.',
    },
    {
      title: '2. Categories of Personal Data',
      body: 'Identity and verification data is collected to fulfil <cite id="kyc">KYC obligations</cite>. Transactional data is retained to satisfy <cite id="aml">AML policy</cite> requirements. Device and behavioural data used for fraud prevention is governed by the <cite id="cookie">Cookie Policy</cite>. No special-category data is processed except where legally mandated.',
    },
    {
      title: '3. Retention Periods & Data Subject Rights',
      body: 'Transaction records are retained for seven years in line with AML requirements. Identity documents are retained for five years post-contract termination. Users may exercise <cite id="gdpr">GDPR Arts. 15–22</cite> rights — including access, erasure, portability, and objection — via the bunq in-app Privacy Centre. Erasure requests are assessed against retention obligations before action is taken.',
    },
    {
      title: '4. International Transfers',
      body: 'Where personal data is transferred outside the EEA, bunq relies on Standard Contractual Clauses (SCCs) approved under the GDPR. Transfer impact assessments are conducted for all third-country recipients. A list of sub-processors and their locations is maintained and published in the Privacy Centre.',
    },
  ],
  '1': [
    {
      title: '1. Application & Licensing',
      body: 'These General Terms & Conditions govern all personal accounts held with bunq B.V., a company incorporated in the Netherlands and authorised as a bank by the <cite id="dnb">De Nederlandsche Bank (DNB)</cite>. bunq operates across the EEA under <cite id="eupassport">EU passport rights</cite>. By opening an account you accept these terms in full.',
    },
    {
      title: '2. Data & Privacy',
      body: 'Personal data is processed in accordance with our <cite id="privacy">Privacy Policy & GDPR Statement</cite> and all applicable <cite id="gdpr">GDPR</cite> obligations. Cookie collection and tracking are governed separately by the <cite id="cookie">Cookie Policy</cite>. bunq will never sell personal data to third parties.',
    },
    {
      title: '3. Fees & Pricing',
      body: 'All applicable fees are set out in the <cite id="pricing">Personal Account Fee Schedule</cite>, which is updated quarterly. bunq reserves the right to modify fees subject to 30 days advance notice as required by the EU Payment Services Directive (PSD2). Continued use of the account after the notice period constitutes acceptance.',
    },
    {
      title: '4. AML & Compliance Obligations',
      body: 'bunq is required by law to apply a risk-based <cite id="kyc">KYC</cite> process and ongoing monitoring under the <cite id="aml">AML & KYC Policy Framework</cite>. Screening against international sanctions lists is carried out continuously in line with <cite id="fatf">FATF Recommendations</cite> and the Dutch <cite id="wwft">WWFT</cite>. Account access may be suspended pending enhanced due diligence.',
    },
  ],
  '5': [
    {
      title: 'License Details',
      body: 'bunq B.V. holds a full banking license granted by <cite id="dnb">De Nederlandsche Bank (DNB)</cite> under the Dutch Wet op het financieel toezicht (Wft), effective since 2014. The license covers deposit-taking, payment services, and credit provision, and confers <cite id="eupassport">EU passport rights</cite> across all 30 EEA member states.',
    },
    {
      title: 'Regulatory Scope',
      body: 'Investment services are provided under <cite id="mifid">MiFID II</cite> authorisation. All operations are governed by the <cite id="aml">AML & KYC Policy Framework</cite>. Annual regulatory performance and capital adequacy disclosures are published in the <cite id="report">Annual Report 2025</cite>. Any material change to licensed activities requires prior written approval from DNB.',
    },
  ],
};
