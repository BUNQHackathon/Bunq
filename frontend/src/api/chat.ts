import { API_BASE } from './client';
import { getAuthToken } from '../auth/useAuth';

function authHeader(): Record<string, string> {
  const token = getAuthToken();
  return token ? { Authorization: `Bearer ${token}` } : {};
}

// ─── Types ────────────────────────────────────────────────
export type KbType = 'REGULATIONS' | 'POLICIES' | 'CONTROLS';

export interface GraphRef {
  launchId: string;
  launchName: string;
  jurisdictionCode: string;
  jurisdictionName: string;
}

export interface Citation {
  kbType: KbType;
  chunkId: string;
  score: number;
  s3Uri: string;
  sourceText: string;
  displayName?: string | null;
  sha256?: string;
  docId?: string;
  documentId?: string;
}

export interface TokenUsage {
  inputTokens?: number;
  outputTokens?: number;
  cachedInputTokens?: number;
}

export interface ChatStartedEvent {
  chatId: string;
  sessionId: string;
  timestamp: string;
}

export interface ChatCompletedEvent {
  chatId: string;
  messageId: string;
  tokenUsage: TokenUsage;
  timestamp: string;
}

export interface ChatFailedEvent {
  chatId: string;
  errorCode: string;
  message: string;
  timestamp: string;
}

export interface ChatStreamHandlers {
  onStarted?: (e: ChatStartedEvent) => void;
  onCitations?: (citations: Citation[]) => void;
  onDelta: (delta: string) => void;
  onCompleted?: (e: ChatCompletedEvent) => void;
  onFailed: (e: ChatFailedEvent) => void;
  onGraphRefs?: (refs: GraphRef[]) => void;
}

export interface ChatRequest {
  query: string;
  chatId?: string;
  sessionId?: string;
}

// ─── History types ─────────────────────────────────────────
export interface ChatHistoryMessage {
  id: string;
  chatId: string;
  role: 'USER' | 'ASSISTANT';
  content: string;
  citations: Citation[];
  graphRefs?: GraphRef[];
  timestamp: string;
  tokenUsage?: TokenUsage;
}

export interface ChatHistory {
  chatId: string;
  messages: ChatHistoryMessage[];
}

// ─── Helpers ──────────────────────────────────────────────
export function citationFileName(s3Uri: string): string {
  // "s3://bucket/path/file.pdf#hash" → "file.pdf"
  const withoutHash = s3Uri.split('#')[0];
  const parts = withoutHash.split('/');
  return parts[parts.length - 1] || s3Uri;
}

// ─── Streaming ────────────────────────────────────────────
export async function postChatStream(
  req: ChatRequest,
  handlers: ChatStreamHandlers,
  signal?: AbortSignal,
): Promise<void> {
  const res = await fetch(`${API_BASE}/chat`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
      ...authHeader(),
    },
    body: JSON.stringify(req),
    signal,
  });
  if (!res.ok || !res.body) {
    handlers.onFailed({
      chatId: req.chatId ?? '',
      errorCode: 'HTTP_' + res.status,
      message: `Chat request failed: ${res.status} ${res.statusText}`,
      timestamp: new Date().toISOString(),
    });
    return;
  }

  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';

  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });

      let sep;
      while ((sep = buffer.indexOf('\n\n')) !== -1) {
        const raw = buffer.slice(0, sep);
        buffer = buffer.slice(sep + 2);
        if (!raw.trim()) continue;

        let eventName = 'message';
        let data = '';
        for (const line of raw.split('\n')) {
          if (line.startsWith('event:')) eventName = line.slice(6).trim();
          else if (line.startsWith('data:')) data += line.slice(5).trim();
        }

        if (eventName === 'chat_delta') {
          try {
            const parsed = JSON.parse(data) as { delta?: string };
            if (parsed.delta) handlers.onDelta(parsed.delta);
          } catch { /* skip */ }
        } else if (eventName === 'chat_citations') {
          try {
            const parsed = JSON.parse(data) as { citations: Citation[] };
            handlers.onCitations?.(parsed.citations ?? []);
          } catch { /* skip */ }
        } else if (eventName === 'chat_started') {
          try {
            const parsed = JSON.parse(data) as ChatStartedEvent;
            handlers.onStarted?.(parsed);
          } catch { /* skip */ }
        } else if (eventName === 'chat_completed') {
          try {
            const parsed = JSON.parse(data) as ChatCompletedEvent;
            handlers.onCompleted?.(parsed);
          } catch { /* skip */ }
        } else if (eventName === 'chat_failed') {
          try {
            const parsed = JSON.parse(data) as ChatFailedEvent;
            handlers.onFailed(parsed);
          } catch {
            handlers.onFailed({
              chatId: req.chatId ?? '',
              errorCode: 'PARSE_ERROR',
              message: 'Malformed chat_failed event',
              timestamp: new Date().toISOString(),
            });
          }
        } else if (eventName === 'graph_refs') {
          try {
            const parsed = JSON.parse(data) as { refs: GraphRef[] };
            handlers.onGraphRefs?.(parsed.refs ?? []);
          } catch { /* skip */ }
        }
        // event: connected — ignore
      }
    }
  } catch (err) {
    if ((err as Error).name === 'AbortError') return;
    handlers.onFailed({
      chatId: req.chatId ?? '',
      errorCode: 'STREAM_ERROR',
      message: err instanceof Error ? err.message : 'Stream error',
      timestamp: new Date().toISOString(),
    });
  }
}

// ─── Non-streaming RAG ────────────────────────────────────
export interface SimpleCitation {
  text: string;
  source: string; // S3 URI
}

export interface RagQueryRequest {
  query: string;
  jurisdiction?: string;
}

export interface RagQueryResponse {
  answer: string;
  citations: SimpleCitation[];
}

export async function postRagQuery(req: RagQueryRequest): Promise<RagQueryResponse> {
  const res = await fetch(`${API_BASE}/rag/query`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json', ...authHeader() },
    body: JSON.stringify(req),
  });
  if (!res.ok) {
    throw new Error(`RAG query failed: ${res.status} ${res.statusText}`);
  }
  return res.json() as Promise<RagQueryResponse>;
}

// Helper: convert SimpleCitation → Citation (unified render)
export function simpleCitationToCitation(s: SimpleCitation): Citation {
  // Extract kbType from S3 path e.g. s3://launchlens-kb-policies/... → POLICIES
  const m = s.source.match(/s3:\/\/launchlens-kb-([a-z]+)\//i);
  const kbType = m ? (m[1].toUpperCase() as Citation['kbType']) : 'REGULATIONS';
  const hashIdx = s.source.indexOf('#');
  const chunkId = hashIdx !== -1 ? s.source.slice(hashIdx + 1) : (s.source.split('/').pop() ?? '');
  const s3Uri = hashIdx !== -1 ? s.source.slice(0, hashIdx) : s.source;
  return {
    kbType,
    chunkId,
    score: 0,
    s3Uri,
    sourceText: s.text,
  };
}

// ─── History ──────────────────────────────────────────────
export async function getChatHistory(chatId: string): Promise<ChatHistory> {
  const res = await fetch(`${API_BASE}/chat/${encodeURIComponent(chatId)}/history`, {
    headers: { Accept: 'application/json', ...authHeader() },
  });
  if (!res.ok) throw new Error(`History fetch failed: ${res.status}`);
  return res.json() as Promise<ChatHistory>;
}

// ─── List ─────────────────────────────────────────────────
export interface ChatSummary {
  chatId: string;
  sessionId?: string | null;
  title: string;
  createdAt: string;
  updatedAt: string;
  messageCount: number;
}

export async function listChats(limit = 100): Promise<ChatSummary[]> {
  const res = await fetch(`${API_BASE}/chat?limit=${limit}`, {
    headers: { Accept: 'application/json', ...authHeader() },
  });
  if (!res.ok) throw new Error(`Chat list failed: ${res.status}`);
  return res.json() as Promise<ChatSummary[]>;
}
