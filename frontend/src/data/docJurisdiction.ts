const STORAGE_KEY = 'bunq.docJurisdictions.v1';

function readMap(): Record<string, string> {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return {};
    const parsed = JSON.parse(raw);
    return parsed && typeof parsed === 'object' ? parsed as Record<string, string> : {};
  } catch {
    return {};
  }
}

function writeMap(map: Record<string, string>): void {
  try { localStorage.setItem(STORAGE_KEY, JSON.stringify(map)); } catch { /* noop */ }
}

export function getDocJurisdiction(docId: string): string | undefined {
  return readMap()[docId];
}

export function setDocJurisdiction(docId: string, code: string): void {
  const m = readMap();
  m[docId] = code;
  writeMap(m);
}

export function getAllDocJurisdictions(): Record<string, string> {
  return readMap();
}
