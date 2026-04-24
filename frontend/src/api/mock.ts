// ─── Inline types (mirrors launch.ts / jurisdictions.ts to avoid circular imports) ──

type Verdict = 'GREEN' | 'AMBER' | 'RED' | 'PENDING';
type JurisdictionStatus = 'RUNNING' | 'COMPLETE' | 'FAILED' | 'PENDING';
type LaunchKind = 'PRODUCT' | 'POLICY' | 'PROCESS';

interface Launch {
  id: string;
  name: string;
  brief: string;
  license?: string;
  counterparties?: string[];
  kind?: LaunchKind;
  aggregateVerdict?: Verdict;
  jurisdictionCount?: number;
  status: 'DRAFT' | 'RUNNING' | 'COMPLETE' | 'FAILED';
  createdAt: string;
  updatedAt: string;
  markets?: string[];
}

interface JurisdictionRun {
  launchId: string;
  jurisdictionCode: string;
  currentSessionId?: string;
  verdict: Verdict;
  gapsCount: number;
  sanctionsHits: number;
  obligationsCovered?: number;
  obligationsTotal?: number;
  proofPackS3Key?: string;
  lastRunAt?: string;
  status: JurisdictionStatus;
}

interface LaunchDetail {
  launch: Launch;
  jurisdictions: JurisdictionRun[];
}

interface JurisdictionOverview {
  code: string;
  aggregateVerdict: Verdict;
  launchCount: number;
  worstVerdict: Verdict;
}

interface JurisdictionLaunchRow {
  launchId: string;
  name: string;
  kind: LaunchKind;
  verdict: Verdict;
  gapsCount: number;
  sanctionsHits: number;
  lastRunAt?: string;
  proofPackAvailable: boolean;
}

interface ComplianceGraphNode {
  id: string;
  type: 'obligation' | 'control' | 'gap' | 'evidence';
  label: string;
  status?: string;
  severity?: 'low' | 'medium' | 'high' | 'critical';
  recommendedAction?: string;
}

interface ComplianceGraphEdge {
  source: string;
  target: string;
  type: 'maps_to' | 'covers' | 'has_gap' | 'evidenced_by';
}

interface ComplianceGraphPayload {
  nodes: ComplianceGraphNode[];
  edges: ComplianceGraphEdge[];
}

// ─── GraphRef helper (used by chat mock heuristic) ────────────────────────────
export interface GraphRef {
  launchId: string;
  launchName: string;
  jurisdictionCode: string;
  jurisdictionName: string;
}

const LAUNCH_NAME_MAP: Record<string, string> = {
  'Crypto Card': 'crypto-card',
  'ToC': 'toc-5-3',
  'KYC': 'kyc-flow',
};

const COUNTRY_CODE_MAP: Record<string, string> = {
  NL: 'NL', Netherlands: 'NL',
  DE: 'DE', Germany: 'DE',
  FR: 'FR', France: 'FR',
  GB: 'GB', Britain: 'GB', UK: 'GB',
  US: 'US',
  IE: 'IE', Ireland: 'IE',
};

const COUNTRY_NAME_MAP: Record<string, string> = {
  NL: 'Netherlands', DE: 'Germany', FR: 'France',
  GB: 'United Kingdom', US: 'United States', IE: 'Ireland',
};

const LAUNCH_LABEL_MAP: Record<string, string> = {
  'crypto-card': 'Crypto Debit Card',
  'toc-5-3': 'ToC §5.3 — Sanctions Screening',
  'kyc-flow': 'KYC Onboarding Flow',
};

export function matchGraphRefsFromPrompt(prompt: string): GraphRef[] {
  const refs: GraphRef[] = [];
  const matchedLaunches: string[] = [];
  const matchedCodes: string[] = [];

  for (const [nameKey, launchId] of Object.entries(LAUNCH_NAME_MAP)) {
    if (prompt.includes(nameKey)) matchedLaunches.push(launchId);
  }
  for (const [nameKey, code] of Object.entries(COUNTRY_CODE_MAP)) {
    if (prompt.includes(nameKey) && !matchedCodes.includes(code)) {
      matchedCodes.push(code);
    }
  }
  for (const launchId of matchedLaunches) {
    for (const code of matchedCodes) {
      refs.push({
        launchId,
        launchName: LAUNCH_LABEL_MAP[launchId] ?? launchId,
        jurisdictionCode: code,
        jurisdictionName: COUNTRY_NAME_MAP[code] ?? code,
      });
    }
  }
  return refs;
}

// ─── Seed data ─────────────────────────────────────────────────────────────────

const NOW_TS = new Date().toISOString();
const YESTERDAY_TS = new Date(Date.now() - 86400000).toISOString();
const TWO_DAYS_AGO_TS = new Date(Date.now() - 172800000).toISOString();

function jr(
  launchId: string,
  jurisdictionCode: string,
  verdict: Verdict,
  gapsCount: number,
  sanctionsHits: number,
  lastRunAt: string,
): JurisdictionRun {
  return {
    launchId,
    jurisdictionCode,
    verdict,
    gapsCount,
    sanctionsHits,
    lastRunAt,
    status: 'COMPLETE',
    obligationsCovered: verdict === 'GREEN' ? 10 : verdict === 'AMBER' ? 7 : 4,
    obligationsTotal: 10,
  };
}

export const MOCK_LAUNCHES: Launch[] = [
  {
    id: 'crypto-card',
    name: 'Crypto Debit Card',
    brief: 'Launch a crypto-linked debit card product across EU and US markets.',
    license: 'EMI',
    kind: 'PRODUCT',
    status: 'COMPLETE',
    aggregateVerdict: 'RED',
    jurisdictionCount: 5,
    markets: ['NL', 'DE', 'FR', 'GB', 'US'],
    createdAt: TWO_DAYS_AGO_TS,
    updatedAt: YESTERDAY_TS,
  },
  {
    id: 'toc-5-3',
    name: 'ToC §5.3 — Sanctions Screening',
    brief: 'Internal policy update for sanctions screening procedures per ToC §5.3.',
    kind: 'POLICY',
    status: 'COMPLETE',
    aggregateVerdict: 'AMBER',
    jurisdictionCount: 4,
    markets: ['NL', 'DE', 'FR', 'IE'],
    createdAt: TWO_DAYS_AGO_TS,
    updatedAt: YESTERDAY_TS,
  },
  {
    id: 'kyc-flow',
    name: 'KYC Onboarding Flow',
    brief: 'Revised KYC onboarding process for retail customers.',
    kind: 'PROCESS',
    status: 'COMPLETE',
    aggregateVerdict: 'RED',
    jurisdictionCount: 5,
    markets: ['NL', 'DE', 'GB', 'US', 'IE'],
    createdAt: TWO_DAYS_AGO_TS,
    updatedAt: YESTERDAY_TS,
  },
];

const CRYPTO_RUNS: JurisdictionRun[] = [
  jr('crypto-card', 'NL', 'AMBER', 3, 1, YESTERDAY_TS),
  jr('crypto-card', 'DE', 'RED', 5, 0, YESTERDAY_TS),
  jr('crypto-card', 'FR', 'AMBER', 2, 0, YESTERDAY_TS),
  jr('crypto-card', 'GB', 'RED', 4, 2, YESTERDAY_TS),
  jr('crypto-card', 'US', 'RED', 6, 0, YESTERDAY_TS),
];

const TOC_RUNS: JurisdictionRun[] = [
  jr('toc-5-3', 'NL', 'GREEN', 0, 0, YESTERDAY_TS),
  jr('toc-5-3', 'DE', 'GREEN', 0, 0, YESTERDAY_TS),
  jr('toc-5-3', 'FR', 'AMBER', 1, 0, YESTERDAY_TS),
  jr('toc-5-3', 'IE', 'GREEN', 0, 0, YESTERDAY_TS),
];

const KYC_RUNS: JurisdictionRun[] = [
  jr('kyc-flow', 'NL', 'AMBER', 2, 0, YESTERDAY_TS),
  jr('kyc-flow', 'DE', 'RED', 3, 1, YESTERDAY_TS),
  jr('kyc-flow', 'GB', 'AMBER', 2, 0, YESTERDAY_TS),
  jr('kyc-flow', 'US', 'RED', 4, 0, YESTERDAY_TS),
  jr('kyc-flow', 'IE', 'GREEN', 0, 0, YESTERDAY_TS),
];

export const MOCK_LAUNCH_DETAIL: Record<string, LaunchDetail> = {
  'crypto-card': { launch: MOCK_LAUNCHES[0], jurisdictions: CRYPTO_RUNS },
  'toc-5-3': { launch: MOCK_LAUNCHES[1], jurisdictions: TOC_RUNS },
  'kyc-flow': { launch: MOCK_LAUNCHES[2], jurisdictions: KYC_RUNS },
};

// ─── Jurisdiction overview ────────────────────────────────────────────────────

export const MOCK_JURISDICTIONS_OVERVIEW: JurisdictionOverview[] = [
  { code: 'NL', aggregateVerdict: 'AMBER', launchCount: 3, worstVerdict: 'AMBER' },
  { code: 'DE', aggregateVerdict: 'RED', launchCount: 3, worstVerdict: 'RED' },
  { code: 'FR', aggregateVerdict: 'AMBER', launchCount: 2, worstVerdict: 'AMBER' },
  { code: 'GB', aggregateVerdict: 'RED', launchCount: 2, worstVerdict: 'RED' },
  { code: 'US', aggregateVerdict: 'RED', launchCount: 2, worstVerdict: 'RED' },
  { code: 'IE', aggregateVerdict: 'GREEN', launchCount: 2, worstVerdict: 'GREEN' },
];

export const MOCK_JURISDICTION_LAUNCHES: Record<string, JurisdictionLaunchRow[]> = {
  NL: [
    { launchId: 'crypto-card', name: 'Crypto Debit Card', kind: 'PRODUCT', verdict: 'AMBER', gapsCount: 3, sanctionsHits: 1, lastRunAt: YESTERDAY_TS, proofPackAvailable: true },
    { launchId: 'toc-5-3', name: 'ToC §5.3 — Sanctions Screening', kind: 'POLICY', verdict: 'GREEN', gapsCount: 0, sanctionsHits: 0, lastRunAt: YESTERDAY_TS, proofPackAvailable: true },
    { launchId: 'kyc-flow', name: 'KYC Onboarding Flow', kind: 'PROCESS', verdict: 'AMBER', gapsCount: 2, sanctionsHits: 0, lastRunAt: YESTERDAY_TS, proofPackAvailable: true },
  ],
  DE: [
    { launchId: 'crypto-card', name: 'Crypto Debit Card', kind: 'PRODUCT', verdict: 'RED', gapsCount: 5, sanctionsHits: 0, lastRunAt: YESTERDAY_TS, proofPackAvailable: true },
    { launchId: 'toc-5-3', name: 'ToC §5.3 — Sanctions Screening', kind: 'POLICY', verdict: 'GREEN', gapsCount: 0, sanctionsHits: 0, lastRunAt: YESTERDAY_TS, proofPackAvailable: true },
    { launchId: 'kyc-flow', name: 'KYC Onboarding Flow', kind: 'PROCESS', verdict: 'RED', gapsCount: 3, sanctionsHits: 1, lastRunAt: YESTERDAY_TS, proofPackAvailable: true },
  ],
  FR: [
    { launchId: 'crypto-card', name: 'Crypto Debit Card', kind: 'PRODUCT', verdict: 'AMBER', gapsCount: 2, sanctionsHits: 0, lastRunAt: YESTERDAY_TS, proofPackAvailable: true },
    { launchId: 'toc-5-3', name: 'ToC §5.3 — Sanctions Screening', kind: 'POLICY', verdict: 'AMBER', gapsCount: 1, sanctionsHits: 0, lastRunAt: YESTERDAY_TS, proofPackAvailable: true },
  ],
  GB: [
    { launchId: 'crypto-card', name: 'Crypto Debit Card', kind: 'PRODUCT', verdict: 'RED', gapsCount: 4, sanctionsHits: 2, lastRunAt: YESTERDAY_TS, proofPackAvailable: true },
    { launchId: 'kyc-flow', name: 'KYC Onboarding Flow', kind: 'PROCESS', verdict: 'AMBER', gapsCount: 2, sanctionsHits: 0, lastRunAt: YESTERDAY_TS, proofPackAvailable: true },
  ],
  US: [
    { launchId: 'crypto-card', name: 'Crypto Debit Card', kind: 'PRODUCT', verdict: 'RED', gapsCount: 6, sanctionsHits: 0, lastRunAt: YESTERDAY_TS, proofPackAvailable: true },
    { launchId: 'kyc-flow', name: 'KYC Onboarding Flow', kind: 'PROCESS', verdict: 'RED', gapsCount: 4, sanctionsHits: 0, lastRunAt: YESTERDAY_TS, proofPackAvailable: true },
  ],
  IE: [
    { launchId: 'toc-5-3', name: 'ToC §5.3 — Sanctions Screening', kind: 'POLICY', verdict: 'GREEN', gapsCount: 0, sanctionsHits: 0, lastRunAt: YESTERDAY_TS, proofPackAvailable: true },
    { launchId: 'kyc-flow', name: 'KYC Onboarding Flow', kind: 'PROCESS', verdict: 'GREEN', gapsCount: 0, sanctionsHits: 0, lastRunAt: YESTERDAY_TS, proofPackAvailable: true },
  ],
};

// ─── Compliance map fixtures ──────────────────────────────────────────────────

function makeComplianceMap(
  obligationLabel: string,
  controlLabel: string,
  evidenceLabel: string,
  gapLabel: string,
  gapSeverity: 'low' | 'medium' | 'high' | 'critical',
  gapAction: string,
  includeGap: boolean,
  prefix: string,
): ComplianceGraphPayload {
  const nodes: ComplianceGraphNode[] = [
    { id: `${prefix}-obl1`, type: 'obligation', label: obligationLabel, status: 'covered' },
    { id: `${prefix}-obl2`, type: 'obligation', label: `${obligationLabel} — reporting`, status: includeGap ? 'partial' : 'covered' },
    { id: `${prefix}-ctrl1`, type: 'control', label: controlLabel, status: 'covered' },
    { id: `${prefix}-ctrl2`, type: 'control', label: `${controlLabel} — monitoring`, status: includeGap ? 'partial' : 'covered' },
    { id: `${prefix}-ctrl3`, type: 'control', label: `${controlLabel} — audit trail`, status: 'covered' },
    { id: `${prefix}-ev1`, type: 'evidence', label: evidenceLabel, status: 'covered' },
    { id: `${prefix}-ev2`, type: 'evidence', label: `${evidenceLabel} — v2`, status: 'covered' },
    { id: `${prefix}-ev3`, type: 'evidence', label: `${evidenceLabel} — policy`, status: 'covered' },
    { id: `${prefix}-ev4`, type: 'evidence', label: `${evidenceLabel} — log`, status: 'covered' },
  ];

  const edges: ComplianceGraphEdge[] = [
    { source: `${prefix}-obl1`, target: `${prefix}-ctrl1`, type: 'maps_to' },
    { source: `${prefix}-obl2`, target: `${prefix}-ctrl2`, type: 'maps_to' },
    { source: `${prefix}-ctrl1`, target: `${prefix}-ev1`, type: 'covers' },
    { source: `${prefix}-ctrl1`, target: `${prefix}-ev2`, type: 'covers' },
    { source: `${prefix}-ctrl2`, target: `${prefix}-ev3`, type: 'covers' },
    { source: `${prefix}-ctrl3`, target: `${prefix}-ev4`, type: 'evidenced_by' },
    { source: `${prefix}-obl1`, target: `${prefix}-ctrl3`, type: 'maps_to' },
  ];

  if (includeGap) {
    nodes.push({
      id: `${prefix}-gap1`,
      type: 'gap',
      label: gapLabel,
      status: 'missing',
      severity: gapSeverity,
      recommendedAction: gapAction,
    });
    edges.push({ source: `${prefix}-obl2`, target: `${prefix}-gap1`, type: 'has_gap' });
    edges.push({ source: `${prefix}-ctrl2`, target: `${prefix}-gap1`, type: 'has_gap' });
  }

  return { nodes, edges };
}

export const MOCK_COMPLIANCE_MAP: Record<string, ComplianceGraphPayload> = {
  'crypto-card:NL': makeComplianceMap(
    'DNB Wwft Art 3', 'AFM MiFID §2 control', 'CBS reporting package',
    'MiCA Art 75 — sanctions gap', 'high',
    'Update ToC §5.3 to include real-time OFAC screening', true, 'cc-nl',
  ),
  'crypto-card:DE': makeComplianceMap(
    'BaFin GwG §10', 'KWG §25i control', 'BaFin filing 2024',
    'GwG §10(3) CDD gap', 'critical',
    'Implement enhanced due diligence for high-risk customers', true, 'cc-de',
  ),
  'crypto-card:FR': makeComplianceMap(
    'ACPR Code Monétaire L561-2', 'AMF MiFID transposition', 'ACPR declaration 2024',
    'L561-2 reporting gap', 'medium',
    'Submit quarterly ACPR report for crypto-asset activity', true, 'cc-fr',
  ),
  'crypto-card:GB': makeComplianceMap(
    'FCA SYSC 6', 'HMRC MLR 2017 control', 'FCA SUP filing',
    'MLR Reg 28 sanctions gap', 'critical',
    'Register with HMRC and implement full sanctions screening', true, 'cc-gb',
  ),
  'crypto-card:US': makeComplianceMap(
    'FinCEN 31 CFR §1020', 'OFAC SDN screening control', 'SAR filing 2024',
    'BSA §5318 reporting gap', 'critical',
    'File SARs and implement FinCEN CTR reporting above $10k', true, 'cc-us',
  ),
  'toc-5-3:NL': makeComplianceMap(
    'DNB Wwft Art 3', 'Sanctions list update process', 'Policy v3.2 sign-off',
    '', 'low', '', false, 'toc-nl',
  ),
  'toc-5-3:DE': makeComplianceMap(
    'BaFin GwG §10', 'Consolidation screening control', 'GwG policy evidence',
    '', 'low', '', false, 'toc-de',
  ),
  'toc-5-3:FR': makeComplianceMap(
    'ACPR Code Monétaire L561-2', 'ACPR sanctions procedure', 'ACPR policy filing',
    'FR gap: DGFIP coordination', 'medium',
    'Add DGFIP sanctions list to screening sources', true, 'toc-fr',
  ),
  'toc-5-3:IE': makeComplianceMap(
    'CBI CJA 2010', 'CBI sanctions monitoring', 'CJA compliance evidence',
    '', 'low', '', false, 'toc-ie',
  ),
  'kyc-flow:NL': makeComplianceMap(
    'DNB Wwft Art 3', 'AFM onboarding control', 'KYC onboarding log NL',
    'Wwft Art 3 CDD gap', 'medium',
    'Enhance source-of-wealth checks for high-risk segments', true, 'kyc-nl',
  ),
  'kyc-flow:DE': makeComplianceMap(
    'BaFin GwG §10', 'KWG §25i KYC procedure', 'GwG onboarding evidence',
    'GwG §10(3) PEP gap', 'high',
    'Implement PEP screening at onboarding step 2', true, 'kyc-de',
  ),
  'kyc-flow:GB': makeComplianceMap(
    'FCA SYSC 6', 'HMRC MLR 2017 KYC', 'FCA KYC audit 2024',
    'MLR Reg 28 EDD gap', 'medium',
    'Add enhanced due diligence for non-EEA customers', true, 'kyc-gb',
  ),
  'kyc-flow:US': makeComplianceMap(
    'FinCEN 31 CFR §1020', 'CIP Rule 31 CFR §1020.220', 'FinCEN CIP evidence',
    'OFAC SDN ongoing screening gap', 'high',
    'Implement continuous OFAC screening post-onboarding', true, 'kyc-us',
  ),
  'kyc-flow:IE': makeComplianceMap(
    'CBI CJA 2010', 'CBI AML KYC control', 'CJA KYC evidence',
    '', 'low', '', false, 'kyc-ie',
  ),
};

// ─── Internal mutable state ────────────────────────────────────────────────────

let _launches = [...MOCK_LAUNCHES];
const _details: Record<string, LaunchDetail> = {
  'crypto-card': { launch: MOCK_LAUNCHES[0], jurisdictions: [...CRYPTO_RUNS] },
  'toc-5-3': { launch: MOCK_LAUNCHES[1], jurisdictions: [...TOC_RUNS] },
  'kyc-flow': { launch: MOCK_LAUNCHES[2], jurisdictions: [...KYC_RUNS] },
};

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function randomDelay(): Promise<void> {
  return delay(200 + Math.random() * 200);
}

// ─── Route handlers ───────────────────────────────────────────────────────────

function handleGetLaunches(): Launch[] {
  return _launches;
}

function handleGetLaunch(id: string): LaunchDetail {
  const detail = _details[id];
  if (!detail) throw new Error(`mock: launch not found: ${id}`);
  return detail;
}

function handlePostLaunches(body: unknown): Launch {
  const req = body as {
    name?: string;
    brief?: string;
    license?: string;
    markets?: string[];
    kind?: LaunchKind;
  };
  const id = `launch-${Date.now()}`;
  const newLaunch: Launch = {
    id,
    name: req.name ?? 'New Launch',
    brief: req.brief ?? '',
    license: req.license,
    kind: req.kind,
    status: 'DRAFT',
    aggregateVerdict: 'PENDING',
    jurisdictionCount: 0,
    markets: req.markets ?? [],
    createdAt: NOW_TS,
    updatedAt: NOW_TS,
  };
  _launches = [..._launches, newLaunch];
  _details[id] = { launch: newLaunch, jurisdictions: [] };
  return newLaunch;
}

function handlePostJurisdiction(launchId: string, code: string): JurisdictionRun {
  const detail = _details[launchId];
  if (!detail) throw new Error(`mock: launch not found: ${launchId}`);
  const run: JurisdictionRun = {
    launchId,
    jurisdictionCode: code,
    verdict: 'PENDING',
    gapsCount: 0,
    sanctionsHits: 0,
    status: 'RUNNING',
    lastRunAt: new Date().toISOString(),
  };
  const existing = detail.jurisdictions.findIndex((j) => j.jurisdictionCode === code);
  if (existing >= 0) {
    detail.jurisdictions[existing] = run;
  } else {
    detail.jurisdictions = [...detail.jurisdictions, run];
  }
  // Simulate async resolution after 3s
  setTimeout(() => {
    const seededVerdicts: Record<string, Verdict> = {
      NL: 'AMBER', DE: 'RED', FR: 'AMBER', GB: 'RED', US: 'RED', IE: 'GREEN',
    };
    const resolved = detail.jurisdictions.find((j) => j.jurisdictionCode === code);
    if (resolved) {
      resolved.verdict = seededVerdicts[code] ?? 'AMBER';
      resolved.status = 'COMPLETE';
      resolved.gapsCount = seededVerdicts[code] === 'GREEN' ? 0 : seededVerdicts[code] === 'AMBER' ? 2 : 3;
      resolved.lastRunAt = new Date().toISOString();
      const launchIdx = _launches.findIndex((l) => l.id === launchId);
      if (launchIdx >= 0) {
        const verdicts = detail.jurisdictions.map((j) => j.verdict);
        const agg: Verdict = verdicts.includes('RED') ? 'RED' : verdicts.includes('AMBER') ? 'AMBER' : 'GREEN';
        _launches[launchIdx] = {
          ..._launches[launchIdx],
          aggregateVerdict: agg,
          jurisdictionCount: detail.jurisdictions.length,
        };
        detail.launch = _launches[launchIdx];
      }
    }
  }, 3000);
  return run;
}

// ─── Public API ───────────────────────────────────────────────────────────────

export async function mockGet<T>(path: string): Promise<T> {
  await randomDelay();

  if (path === '/launches') {
    return handleGetLaunches() as unknown as T;
  }

  const complianceMatch = path.match(/^\/launches\/([^/]+)\/jurisdictions\/([^/]+)\/compliance-map$/);
  if (complianceMatch) {
    return handleGetComplianceMap(complianceMatch[1], complianceMatch[2]) as unknown as T;
  }

  const launchMatch = path.match(/^\/launches\/([^/]+)$/);
  if (launchMatch) {
    return handleGetLaunch(launchMatch[1]) as unknown as T;
  }

  if (path === '/jurisdictions') {
    return MOCK_JURISDICTIONS_OVERVIEW as unknown as T;
  }

  const jLaunchesMatch = path.match(/^\/jurisdictions\/([^/]+)\/launches$/);
  if (jLaunchesMatch) {
    const code = jLaunchesMatch[1];
    const launches = MOCK_JURISDICTION_LAUNCHES[code];
    if (!launches) throw new Error(`mock: no launches for jurisdiction: ${code}`);
    return { code, launches } as unknown as T;
  }

  throw new Error(`mock: unhandled path ${path}`);
}

export async function mockPost<T>(path: string, body?: unknown): Promise<T> {
  await randomDelay();

  if (path === '/launches') {
    return handlePostLaunches(body) as unknown as T;
  }

  const rerunMatch = path.match(/^\/launches\/([^/]+)\/jurisdictions\/([^/]+)\/run$/);
  if (rerunMatch) {
    return handlePostJurisdiction(rerunMatch[1], rerunMatch[2]) as unknown as T;
  }

  const jurisdictionMatch = path.match(/^\/launches\/([^/]+)\/jurisdictions\/([^/]+)$/);
  if (jurisdictionMatch) {
    return handlePostJurisdiction(jurisdictionMatch[1], jurisdictionMatch[2]) as unknown as T;
  }

  throw new Error(`mock: unhandled path ${path}`);
}

function handleGetComplianceMap(launchId: string, code: string): ComplianceGraphPayload {
  const key = `${launchId}:${code}`;
  const map = MOCK_COMPLIANCE_MAP[key];
  if (!map) throw new Error(`mock: no compliance map for ${key}`);
  return map;
}
