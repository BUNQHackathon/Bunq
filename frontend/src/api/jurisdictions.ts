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
  // provenance fields for "View source" affordance
  documentId?: string;
  sourceTextSnippet?: string;
  article?: string;
  section?: string;
  paragraph?: string;
  s3Uri?: string;
}

export interface ComplianceGraphEdge {
  id: string;
  type?: string;
  label?: string;
  // optional — present in some payload variants; otherwise parse from id
  source?: string;
  target?: string;
  // ground-check result from reviewer
  reviewerNotes?: string;
}

export interface ComplianceGraphPayload {
  nodes: ComplianceGraphNode[];
  edges: ComplianceGraphEdge[];
}

export async function listJurisdictionsOverview(): Promise<JurisdictionOverview[]> {
  return getJson<JurisdictionOverview[]>('/jurisdictions');
}

export async function getJurisdictionTriage(code: string, readOnly?: boolean): Promise<JurisdictionTriage> {
  const path = `/jurisdictions/${encodeURIComponent(code)}/triage${readOnly ? '?readOnly=true' : ''}`;
  return getJson<JurisdictionTriage>(path);
}

export async function getComplianceMap(
  launchId: string,
  code: string,
): Promise<ComplianceGraphPayload> {
  const path = `/launches/${encodeURIComponent(launchId)}/jurisdictions/${encodeURIComponent(code)}/compliance-map`;
  if (USE_MOCK) return mockGet<ComplianceGraphPayload>(path);
  return getJson<ComplianceGraphPayload>(path);
}
