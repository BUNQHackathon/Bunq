import { getAuthToken } from '../auth/useAuth';

function normalizeApiBase(raw: string | undefined): string {
  const fallback = 'http://localhost:8080/api/v1';
  const value = (raw && raw.trim()) || fallback;
  const trimmed = value.replace(/\/+$/, '');

  try {
    const url = new URL(trimmed);
    if (url.pathname === '' || url.pathname === '/') {
      url.pathname = '/api/v1';
      return url.toString().replace(/\/+$/, '');
    }
  } catch {
    // Relative API bases are allowed; normalize the common empty-root form only.
    if (trimmed === '') return fallback;
    if (trimmed === '/') return '/api/v1';
  }

  return trimmed;
}

export const API_BASE = normalizeApiBase(import.meta.env.VITE_API_BASE);

const STORAGE_KEY = 'launchlens.auth:token';
const AUTH_EVENT = 'launchlens:auth-change';

function clearAuthAndRedirect(): void {
  localStorage.removeItem(STORAGE_KEY);
  window.dispatchEvent(new CustomEvent(AUTH_EVENT, { detail: null }));
  window.location.assign('/login');
}

function authHeaders(): Record<string, string> {
  const token = getAuthToken();
  return token ? { Authorization: `Bearer ${token}` } : {};
}

export async function getJson<T>(path: string): Promise<T> {
  const res = await fetch(`${API_BASE}${path}`, {
    headers: { Accept: 'application/json', ...authHeaders() },
  });
  if (res.status === 401) { if (getAuthToken()) clearAuthAndRedirect(); throw new Error(`${path} failed: 401`); }
  if (!res.ok) throw new Error(`${path} failed: ${res.status}`);
  return res.json() as Promise<T>;
}

export async function postJson<T>(path: string, body?: unknown): Promise<T> {
  const res = await fetch(`${API_BASE}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json', ...authHeaders() },
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });
  if (res.status === 401) { if (getAuthToken()) clearAuthAndRedirect(); throw new Error(`${path} failed: 401`); }
  if (!res.ok) throw new Error(`${path} failed: ${res.status}`);
  const text = await res.text();
  return (text ? JSON.parse(text) : {}) as T;
}
