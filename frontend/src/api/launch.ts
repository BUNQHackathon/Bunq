import { API_BASE, getJson, postJson } from './client';

/**
 * Thrown by downloadProofPack when the pipeline is still running and the
 * proof pack is not yet available. Callers can `instanceof`-check this to
 * show a user-facing message instead of a generic error.
 */
export class ProofPackNotReadyError extends Error {
  constructor(message = 'Proof pack not ready — pipeline still running') {
    super(message);
    this.name = 'ProofPackNotReadyError';
  }
}

export const USE_MOCK = import.meta.env.VITE_USE_MOCK === 'true';

export type Verdict = 'GREEN' | 'AMBER' | 'RED' | 'PENDING' | 'UNKNOWN';
export type JurisdictionStatus = 'RUNNING' | 'COMPLETE' | 'FAILED' | 'PENDING';
export type LaunchKind = 'PRODUCT' | 'POLICY' | 'PROCESS';

export interface LaunchJurisdictionSummary {
  code: string;
  verdict: Verdict;
  status: string;
}

export interface Launch {
  id: string;
  name: string;
  brief: string;
  license?: string;
  counterparties?: string[];
  kind?: LaunchKind;
  aggregateVerdict?: Verdict;
  jurisdictionCount?: number;
  status: 'DRAFT' | 'RUNNING' | 'COMPLETE' | 'FAILED' | 'CREATED';
  createdAt: string;
  updatedAt: string;
  markets?: string[];
  jurisdictions?: LaunchJurisdictionSummary[];
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
  obligationsCount?: number;
  controlsCount?: number;
  regulationsCovered?: number;
  proofPackS3Key?: string;
  lastRunAt?: string;
  status: JurisdictionStatus;
  summary?: string;
  requiredChanges?: string[];
  blockers?: string[];
  proofPackAvailable?: boolean;
}

export interface LaunchDetail {
  launch: Launch;
  jurisdictions: JurisdictionRun[];
}

export interface CreateLaunchRequest {
  name: string;
  brief: string;
  license?: string;
  jurisdictions?: string[];
  kind?: LaunchKind;
}

export function createLaunch(req: CreateLaunchRequest): Promise<Launch> {
  return postJson<Launch>('/launches', req);
}

export function listLaunches(): Promise<Launch[]> {
  return getJson<Launch[]>('/launches');
}

export async function getLaunch(id: string): Promise<LaunchDetail> {
  const path = `/launches/${encodeURIComponent(id)}`;
  const raw = await getJson<LaunchDetail | Launch>(path);
  const launch = (raw && typeof raw === 'object' && 'launch' in raw
    ? (raw as LaunchDetail).launch
    : (raw as Launch));
  const jurisdictions = (raw && typeof raw === 'object' && 'jurisdictions' in raw && Array.isArray((raw as LaunchDetail).jurisdictions)
    ? (raw as LaunchDetail).jurisdictions
    : []
  ).map(normalizeRun);
  return { launch, jurisdictions };
}

export function normalizeRun(r: Partial<JurisdictionRun> & { verdict?: Verdict | null }): JurisdictionRun {
  const rawVerdict = r.verdict;
  const verdict: Verdict =
    rawVerdict === null || rawVerdict === undefined
      ? 'PENDING'
      : (rawVerdict === 'GREEN' || rawVerdict === 'AMBER' || rawVerdict === 'RED' || rawVerdict === 'PENDING' || rawVerdict === 'UNKNOWN'
        ? rawVerdict
        : 'PENDING');
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
    summary: r.summary,
    requiredChanges: r.requiredChanges,
    blockers: r.blockers,
    proofPackAvailable: r.proofPackAvailable,
  };
}

export function getProofPackUrl(launchId: string, code: string): string {
  return `${API_BASE}/launches/${encodeURIComponent(launchId)}/jurisdictions/${encodeURIComponent(code)}/proof-pack`;
}

export async function downloadProofPack(launchId: string, code: string): Promise<void> {
  const url = getProofPackUrl(launchId, code);
  try {
    const res = await fetch(url, { headers: { Accept: 'application/zip' } });
    if (!res.ok) {
      throw new Error(`HTTP ${res.status}`);
    }
    const blob = await res.blob();
    const objUrl = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = objUrl;
    a.download = `proof-pack-${launchId}-${code}.zip`;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(objUrl);
  } catch (err) {
    throw new ProofPackNotReadyError(
      err instanceof Error ? err.message : 'Proof pack not ready — pipeline still running',
    );
  }
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

export async function deleteLaunch(id: string): Promise<void> {
  const res = await fetch(`${API_BASE}/launches/${encodeURIComponent(id)}`, {
    method: 'DELETE',
  });
  if (!res.ok) throw new Error(`/launches/${id} failed: ${res.status}`);
}

export async function rerunFailedJurisdictions(launchId: string): Promise<JurisdictionRun[]> {
  const res = await postJson<JurisdictionRun[]>(`/launches/${encodeURIComponent(launchId)}/rerun-failed`);
  return (Array.isArray(res) ? res : []).map(normalizeRun);
}

export async function runJurisdiction(launchId: string, code: string): Promise<JurisdictionRun> {
  const res = await postJson<JurisdictionRun>(
    `/launches/${encodeURIComponent(launchId)}/jurisdictions/${encodeURIComponent(code)}/run`,
  );
  return normalizeRun(res);
}

export function jurisdictionLabel(code: string): string {
  return JURISDICTION_CATALOG.find((j) => j.code === code)?.name ?? code;
}

export function jurisdictionFlag(code: string): string {
  return JURISDICTION_CATALOG.find((j) => j.code === code)?.flag ?? '🏳️';
}
