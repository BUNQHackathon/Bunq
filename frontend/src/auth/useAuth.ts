import { useState, useEffect, useCallback } from 'react';

const STORAGE_KEY = 'launchlens.auth:token';
const EVENT_NAME = 'launchlens:auth-change';

export function getAuthToken(): string | null {
  if (typeof window === 'undefined') return null;
  return localStorage.getItem(STORAGE_KEY);
}

export function useAuth(): {
  token: string | null;
  isAuthenticated: boolean;
  login: (token: string) => void;
  logout: () => void;
} {
  const [token, setToken] = useState<string | null>(getAuthToken);

  const login = useCallback((t: string) => {
    localStorage.setItem(STORAGE_KEY, t);
    window.dispatchEvent(new CustomEvent(EVENT_NAME, { detail: t }));
    setToken(t);
  }, []);

  const logout = useCallback(() => {
    localStorage.removeItem(STORAGE_KEY);
    window.dispatchEvent(new CustomEvent(EVENT_NAME, { detail: null }));
    setToken(null);
  }, []);

  useEffect(() => {
    const onStorage = (e: StorageEvent) => {
      if (e.key === STORAGE_KEY) {
        setToken(e.newValue);
      }
    };
    const onCustom = (e: Event) => {
      const detail = (e as CustomEvent<string | null>).detail;
      setToken(detail);
    };
    window.addEventListener('storage', onStorage);
    window.addEventListener(EVENT_NAME, onCustom);
    return () => {
      window.removeEventListener('storage', onStorage);
      window.removeEventListener(EVENT_NAME, onCustom);
    };
  }, []);

  return { token, isAuthenticated: token !== null, login, logout };
}
