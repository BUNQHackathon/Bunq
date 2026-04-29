import { API_BASE } from './client';
import { getAuthToken } from '../auth/useAuth';

export interface StageCost {
  inputTokens: number;
  outputTokens: number;
  cacheCreationTokens: number;
  cacheReadTokens: number;
  usdCents: number;
  models: string[];
}

export interface SessionCost {
  sessionId: string;
  totalInputTokens: number;
  totalOutputTokens: number;
  totalCacheCreationTokens: number;
  totalCacheReadTokens: number;
  totalUsdCents: number;
  perStage: Record<string, StageCost>;
  updatedAt: string;
}

function authHeaders(): Record<string, string> {
  const token = getAuthToken();
  return token ? { Authorization: `Bearer ${token}` } : {};
}

export async function getSessionCost(sessionId: string): Promise<SessionCost | null> {
  const res = await fetch(
    `${API_BASE}/sessions/${encodeURIComponent(sessionId)}/cost`,
    { headers: { Accept: 'application/json', ...authHeaders() } },
  );
  if (res.status === 404) return null;
  if (!res.ok) return null;
  return res.json() as Promise<SessionCost>;
}
