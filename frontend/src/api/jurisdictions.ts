import { getJson } from './client';
import { USE_MOCK } from './launch';
import type { Verdict, LaunchKind } from './launch';
import { mockGet } from './mock';

export interface JurisdictionOverview {
  code: string;
  aggregateVerdict: Verdict;
  launchCount: number;
  worstVerdict: Verdict;
}

export interface JurisdictionLaunchRow {
  launchId: string;
  name: string;
  kind: LaunchKind;
  verdict: Verdict;
  gapsCount: number;
  sanctionsHits: number;
  lastRunAt?: string;
  proofPackAvailable: boolean;
}

export interface ComplianceGraphNode {
  id: string;
  type: 'obligation' | 'control' | 'gap' | 'evidence';
  label: string;
  status?: 'covered' | 'partial' | 'missing' | string;
  severity?: 'low' | 'medium' | 'high' | 'critical';
  recommendedAction?: string;
}

export interface ComplianceGraphEdge {
  source: string;
  target: string;
  type: 'maps_to' | 'covers' | 'has_gap' | 'evidenced_by';
}

export interface ComplianceGraphPayload {
  nodes: ComplianceGraphNode[];
  edges: ComplianceGraphEdge[];
}

export async function listJurisdictionsOverview(): Promise<JurisdictionOverview[]> {
  if (USE_MOCK) return mockGet<JurisdictionOverview[]>('/jurisdictions');
  return getJson<JurisdictionOverview[]>('/jurisdictions');
}

export async function getJurisdictionLaunches(
  code: string,
): Promise<{ code: string; launches: JurisdictionLaunchRow[] }> {
  const path = `/jurisdictions/${encodeURIComponent(code)}/launches`;
  if (USE_MOCK) return mockGet<{ code: string; launches: JurisdictionLaunchRow[] }>(path);
  return getJson<{ code: string; launches: JurisdictionLaunchRow[] }>(path);
}

export async function getComplianceMap(
  launchId: string,
  code: string,
): Promise<ComplianceGraphPayload> {
  const path = `/launches/${encodeURIComponent(launchId)}/jurisdictions/${encodeURIComponent(code)}/compliance-map`;
  if (USE_MOCK) return mockGet<ComplianceGraphPayload>(path);
  return getJson<ComplianceGraphPayload>(path);
}
