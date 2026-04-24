import { API_BASE, getJson, postJson } from './client';

export type Verdict = 'GREEN' | 'AMBER' | 'RED' | 'PENDING';
export type JurisdictionStatus = 'RUNNING' | 'COMPLETE' | 'FAILED' | 'PENDING';

export interface Launch {
  id: string;
  name: string;
  brief: string;
  license?: string;
  counterparties?: string[];
  status: 'DRAFT' | 'RUNNING' | 'COMPLETE' | 'FAILED';
  createdAt: string;
  updatedAt: string;
  markets?: string[];
}

export interface JurisdictionRun {
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

export interface LaunchDetail {
  launch: Launch;
  jurisdictions: JurisdictionRun[];
}

export interface CreateLaunchRequest {
  name: string;
  brief: string;
  license?: string;
  markets: string[];
}

export function createLaunch(req: CreateLaunchRequest): Promise<Launch> {
  return postJson<Launch>('/launches', req);
}

export function listLaunches(): Promise<Launch[]> {
  return getJson<Launch[]>('/launches');
}

export async function getLaunch(id: string): Promise<LaunchDetail> {
  const raw = await getJson<LaunchDetail | Launch>(`/launches/${encodeURIComponent(id)}`);
  const launch = (raw && typeof raw === 'object' && 'launch' in raw
    ? (raw as LaunchDetail).launch
    : (raw as Launch));
  const jurisdictions = (raw && typeof raw === 'object' && 'jurisdictions' in raw && Array.isArray((raw as LaunchDetail).jurisdictions)
    ? (raw as LaunchDetail).jurisdictions
    : []
  ).map(normalizeRun);
  return { launch, jurisdictions };
}

function normalizeRun(r: Partial<JurisdictionRun>): JurisdictionRun {
  const verdict: Verdict =
    r.verdict === 'GREEN' || r.verdict === 'AMBER' || r.verdict === 'RED' || r.verdict === 'PENDING'
      ? r.verdict
      : 'PENDING';
  const status: JurisdictionStatus =
    r.status === 'RUNNING' || r.status === 'COMPLETE' || r.status === 'FAILED' || r.status === 'PENDING'
      ? r.status
      : 'PENDING';
  return {
    launchId: r.launchId ?? '',
    jurisdictionCode: r.jurisdictionCode ?? '',
    currentSessionId: r.currentSessionId,
    verdict,
    gapsCount: r.gapsCount ?? 0,
    sanctionsHits: r.sanctionsHits ?? 0,
    obligationsCovered: r.obligationsCovered,
    obligationsTotal: r.obligationsTotal,
    proofPackS3Key: r.proofPackS3Key,
    lastRunAt: r.lastRunAt,
    status,
  };
}

export function addJurisdiction(launchId: string, code: string): Promise<JurisdictionRun> {
  return postJson<JurisdictionRun>(
    `/launches/${encodeURIComponent(launchId)}/jurisdictions/${encodeURIComponent(code)}`,
    {}
  );
}

export function rerunJurisdiction(launchId: string, code: string): Promise<JurisdictionRun> {
  return postJson<JurisdictionRun>(
    `/launches/${encodeURIComponent(launchId)}/jurisdictions/${encodeURIComponent(code)}/run`,
    {}
  );
}

export function getProofPackUrl(launchId: string, code: string): string {
  return `${API_BASE}/launches/${encodeURIComponent(launchId)}/jurisdictions/${encodeURIComponent(code)}/proof-pack`;
}

export function jurisdictionSseUrl(launchId: string, code: string): string {
  return `${API_BASE}/launches/${encodeURIComponent(launchId)}/jurisdictions/${encodeURIComponent(code)}/stream`;
}

export const JURISDICTION_CATALOG: Array<{ code: string; name: string; flag: string }> = [
  { code: 'NL', name: 'Netherlands', flag: '🇳🇱' },
  { code: 'DE', name: 'Germany', flag: '🇩🇪' },
  { code: 'FR', name: 'France', flag: '🇫🇷' },
  { code: 'AT', name: 'Austria', flag: '🇦🇹' },
  { code: 'ES', name: 'Spain', flag: '🇪🇸' },
  { code: 'IT', name: 'Italy', flag: '🇮🇹' },
  { code: 'IE', name: 'Ireland', flag: '🇮🇪' },
  { code: 'BE', name: 'Belgium', flag: '🇧🇪' },
  { code: 'GB', name: 'United Kingdom', flag: '🇬🇧' },
  { code: 'US', name: 'United States', flag: '🇺🇸' },
];

export function jurisdictionLabel(code: string): string {
  return JURISDICTION_CATALOG.find((j) => j.code === code)?.name ?? code;
}

export function jurisdictionFlag(code: string): string {
  return JURISDICTION_CATALOG.find((j) => j.code === code)?.flag ?? '🏳️';
}
