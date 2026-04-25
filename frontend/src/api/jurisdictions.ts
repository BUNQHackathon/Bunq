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
  jurisdictionCode?: string;
  verdict: Verdict;
  gapsCount: number;
  sanctionsHits: number;
  lastRunAt?: string;
  proofPackAvailable: boolean;
  status?: string;
  summary?: string;
  requiredChanges?: string[];
  blockers?: string[];
}

export interface JurisdictionTriage {
  code: string;
  keep: JurisdictionLaunchRow[];
  modify: JurisdictionLaunchRow[];
  drop: JurisdictionLaunchRow[];
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
  return getJson<JurisdictionOverview[]>('/jurisdictions');
}

export async function getJurisdictionTriage(code: string): Promise<JurisdictionTriage> {
  const path = `/jurisdictions/${encodeURIComponent(code)}/triage`;
  return getJson<JurisdictionTriage>(path);
}

export async function getComplianceMap(
  launchId: string,
  code: string,
): Promise<ComplianceGraphPayload> {
  const path = `/launches/${encodeURIComponent(launchId)}/jurisdictions/${encodeURIComponent(code)}/compliance-map`;
  if (USE_MOCK) return mockGet<ComplianceGraphPayload>(path);
  try {
    return await getJson<ComplianceGraphPayload>(path);
  } catch (err) {
    console.warn(`Compliance map not available yet for ${launchId}/${code}, falling back to mock:`, err);
    return mockGet<ComplianceGraphPayload>(path);
  }
}
