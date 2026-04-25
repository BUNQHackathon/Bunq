import { getJson } from './client';

export interface SearchHit {
  type: string;
  id: string;
  title: string;
  subtitle: string;
}

export interface SearchResponse {
  query: string;
  documents: SearchHit[];
  sessions: SearchHit[];
  obligations: SearchHit[];
  controls: SearchHit[];
  launches: SearchHit[];
  jurisdictions: SearchHit[];
}

export function searchAll(query: string, limit = 5): Promise<SearchResponse> {
  const params = new URLSearchParams({ q: query, limit: String(limit) });
  return getJson<SearchResponse>(`/search?${params.toString()}`);
}
