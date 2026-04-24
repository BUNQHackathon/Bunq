import { getJson } from './client';

// ─── Types ────────────────────────────────────────────────
export interface Jurisdiction {
  code: string;
  name: string;
  flag: string;
  status: 'active' | 'watchlist' | 'restricted';
  license: string;
  regulator: string;
}

export interface DocumentSummary {
  id: string;
  key: string;
  title: string;
  category: string;
  jurisdiction: string;
  type: string;
  size: number;
  updated: string;
}

export interface DocumentSection {
  title: string;
  body: string;
}

export interface DocumentContent {
  id: string;
  title: string;
  category: string;
  jurisdiction: string;
  updated: string;
  downloadUrl: string;
  sections: DocumentSection[];
}

export type GraphCat = 'terms' | 'aml' | 'privacy' | 'licensing' | 'reports' | 'pricing' | 'concept';

export interface GraphNode {
  id: string;
  label: string;
  cat: GraphCat;
  doc: boolean;
  size: number;
  updated: string;
}

export interface GraphLink {
  source: string;
  target: string;
}

export interface GraphData {
  nodes: GraphNode[];
  links: GraphLink[];
}

// ─── Fetchers ─────────────────────────────────────────────
export function listJurisdictions(): Promise<Jurisdiction[]> {
  return getJson<Jurisdiction[]>('/jurisdictions');
}

export function listDocuments(): Promise<DocumentSummary[]> {
  return getJson<DocumentSummary[]>('/kb/regulations');
}

export function getDocument(id: string): Promise<DocumentContent> {
  return getJson<DocumentContent>(`/kb/regulations/${encodeURIComponent(id)}`);
}

// KB corpus type aliases and convenience re-exports
export interface KbRegulationSummary extends DocumentSummary {}
export interface KbRegulationDetail extends DocumentContent {}
export const listKbRegulations = listDocuments;
export const getKbRegulation = getDocument;

export function getGraph(): Promise<GraphData> {
  return getJson<GraphData>('/graph');
}

export interface PresignedUrlResponse {
  url: string;
  expiresAt: string;
}

export async function getPresignedUrl(s3Uri: string): Promise<PresignedUrlResponse> {
  const qs = new URLSearchParams({ s3Uri }).toString();
  return getJson<PresignedUrlResponse>(`/files/presigned-url?${qs}`);
}
