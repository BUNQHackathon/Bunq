import { useEffect, useRef, useState } from 'react';
import { useNavigate, Link } from 'react-router-dom';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import PrismCanvas from '../components/PrismCanvas';
import {
  postChatStream,
  citationFileName,
  getChatHistory,
  listChatKnowledgeBases,
  type Citation,
  type GraphRef,
  type KnowledgeBaseOption,
} from '../api/chat';
import GraphRefChips from '../components/GraphRefChips';
import { matchGraphRefsFromPrompt } from '../api/mock';
import { USE_MOCK } from '../api/launch';
import { useChatNav } from '../lib/chatNav';
import { useAuth } from '../auth/useAuth';
import useJudgesGate from '../auth/useJudgesGate';
import { getLibraryDocument } from '../api/session';

// ─── Local data ───────────────────────────────────────────────────────────────

const suggestedQuestions = [
  'What are our AML obligations in Germany?',
  'Can we offer crypto custody under MiCA?',
  'GDPR retention rules for transaction data',
  'EMI passporting requirements for France',
];

const ALL_SOURCES_OPTION: KnowledgeBaseOption = {
  key: 'all',
  label: 'All sources',
  knowledgeBaseId: null,
  kbType: null,
  defaultOption: true,
};

const citationDocumentNameCache = new Map<string, string>();
const citationDocumentFetches = new Map<string, Promise<string | null>>();

// ─── Local icon components ────────────────────────────────────────────────────

function IconSend({ disabled }: { disabled?: boolean }) {
  return (
    <svg
      width="16"
      height="16"
      viewBox="0 0 16 16"
      fill="none"
      style={{ opacity: disabled ? 0.5 : 1 }}
    >
      <path
        d="M2 8l12-6-6 12V9L2 8z"
        fill="#ef6a2a"
        stroke="#ef6a2a"
        strokeWidth="0.5"
        strokeLinejoin="round"
      />
    </svg>
  );
}

// ─── Sub-components ───────────────────────────────────────────────────────────

const CATEGORIES = [
  { label: 'PRIVACY', color: '#8ee06b' },
  { label: 'AML', color: '#5fd6c6' },
  { label: 'LICENSING', color: '#6ab8ff' },
  { label: 'TERMS', color: '#5a90d4' },
  { label: 'SANCTIONS', color: '#4a8fe8' },
  { label: 'REPORTS', color: '#e03a3a' },
];

function CategoryRow() {
  return (
    <div className="ask__categories flex flex-nowrap sm:flex-wrap sm:justify-center items-center gap-x-4 gap-y-2 md:gap-7 mb-5 px-2 overflow-x-auto" style={{ scrollbarWidth: 'none', opacity: 0.9, pointerEvents: 'none', userSelect: 'none', WebkitUserSelect: 'none' }}>
      {CATEGORIES.map((cat) => (
        <span
          key={cat.label}
          className="font-mono uppercase tracking-[0.2em] text-[11px]"
          style={{ color: cat.color }}
        >
          {cat.label}
        </span>
      ))}
    </div>
  );
}

interface SearchBarProps {
  query: string;
  setQuery: (v: string) => void;
  onSubmit: (e: React.FormEvent<HTMLFormElement>) => void;
  disabled?: boolean;
  placeholder?: string;
}

function SearchBar({ query, setQuery, onSubmit, disabled, placeholder }: SearchBarProps) {
  return (
    <form onSubmit={onSubmit} className="w-full" style={{ maxWidth: '820px', margin: '0 auto' }}>
      <div
        className="flex items-center gap-3 rounded-pill border px-4 py-3"
        style={{
          background: 'rgba(20,20,20,0.8)',
          backdropFilter: 'blur(16px)',
          borderColor: 'rgba(255,255,255,0.08)',
          boxShadow: '0 20px 60px rgba(0,0,0,0.45)',
        }}
      >
        <input
          type="text"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder={placeholder ?? "Ask about bunq's compliance across the EU…"}
          className="flex-1 bg-transparent outline-none text-white text-[15px] min-w-0"
          style={{ caretColor: '#ef6a2a' }}
          disabled={disabled}
        />
        <button
          type="submit"
          disabled={disabled || !query.trim()}
          className="w-10 h-10 rounded-full flex items-center justify-center shrink-0 transition-colors"
          style={{
            background: 'rgba(239,106,42,0.10)',
            border: '1px solid rgba(239,106,42,0.3)',
            opacity: (disabled || !query.trim()) ? 0.5 : 1,
          }}
          aria-label="Send"
        >
          <IconSend disabled={disabled || !query.trim()} />
        </button>
      </div>
    </form>
  );
}

interface KnowledgeBaseSelectorProps {
  options: KnowledgeBaseOption[];
  selectedId: string | null;
  onChange: (knowledgeBaseId: string | null) => void;
  disabled?: boolean;
}

function KnowledgeBaseSelector({ options, selectedId, onChange, disabled }: KnowledgeBaseSelectorProps) {
  return (
    <div className="w-full" style={{ maxWidth: 820, margin: '0 auto 8px' }}>
      <select
        value={selectedId ?? ''}
        onChange={(e) => onChange(e.target.value || null)}
        disabled={disabled}
        className="h-9 max-w-full rounded-lg border px-3 text-[12px] font-medium outline-none transition"
        style={{
          color: 'rgba(255,255,255,0.82)',
          background: 'rgba(20,20,20,0.82)',
          borderColor: 'rgba(255,255,255,0.10)',
          opacity: disabled ? 0.65 : 1,
        }}
        aria-label="Knowledge base"
      >
        {options.map((option) => (
          <option key={option.key} value={option.knowledgeBaseId ?? ''}>
            {option.label}
          </option>
        ))}
      </select>
    </div>
  );
}

interface TryAskingProps {
  onSelect: (q: string) => void;
}

function TryAsking({ onSelect }: TryAskingProps) {
  return (
    <div className="flex flex-wrap justify-center items-center gap-3 mt-8">
      {suggestedQuestions.map((q) => (
        <button
          key={q}
          type="button"
          onClick={() => onSelect(q)}
          className="rounded-pill border px-4 py-2 text-[13px] text-white/80 transition hover:text-white"
          style={{
            background: 'rgba(20,20,20,0.7)',
            borderColor: 'rgba(246,241,234,0.14)',
            backdropFilter: 'blur(10px)',
            WebkitBackdropFilter: 'blur(10px)',
          }}
        >
          {q}
        </button>
      ))}
    </div>
  );
}

// ─── KB type badge colors ─────────────────────────────────────────────────────

const KB_COLORS: Record<string, string> = {
  REGULATIONS: '#6EB7E8',
  POLICIES: '#B08AFF',
  CONTROLS: '#5ECFA0',
};

// ─── Chat message model ──────────────────────────────────────────────────────

type ChatMessage = {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  citations?: Citation[];
  graphRefs?: GraphRef[];
  knowledgeBaseId?: string | null;
  knowledgeBaseLabel?: string | null;
  pending?: boolean;
};

let _msgSeq = 0;
function nextMsgId() {
  _msgSeq += 1;
  return `m${Date.now()}-${_msgSeq}`;
}

async function hydrateMessageCitationNames(messages: ChatMessage[]): Promise<ChatMessage[]> {
  return Promise.all(messages.map(async (message) => {
    if (!message.citations || message.citations.length === 0) {
      return message;
    }
    return { ...message, citations: await hydrateCitationDocumentNames(message.citations) };
  }));
}

// ─── Page ─────────────────────────────────────────────────────────────────────

export default function AskPage() {
  const navigate = useNavigate();
  const [query, setQuery] = useState('');
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [currentChatId, setCurrentChatId] = useState<string | undefined>(undefined);
  const [knowledgeBaseOptions, setKnowledgeBaseOptions] = useState<KnowledgeBaseOption[]>([ALL_SOURCES_OPTION]);
  const [selectedKnowledgeBaseId, setSelectedKnowledgeBaseId] = useState<string | null>(null);
  const abortRef = useRef<AbortController | null>(null);
  const scrollerRef = useRef<HTMLDivElement>(null);

  const { activeChatId, resetToken } = useChatNav();
  const { isAuthenticated } = useAuth();
  const { showGate, modal: judgesModal } = useJudgesGate();
  const CHAT_GATE = {
    title: 'Bunq judges only',
    message: 'Chat is available to Bunq judges. Please sign in with your access token to continue.',
  };
  const selectedKnowledgeBase = knowledgeBaseOptions.find((option) => option.knowledgeBaseId === selectedKnowledgeBaseId)
    ?? knowledgeBaseOptions.find((option) => option.knowledgeBaseId === null)
    ?? ALL_SOURCES_OPTION;

  useEffect(() => {
    if (!isAuthenticated) {
      setKnowledgeBaseOptions([ALL_SOURCES_OPTION]);
      setSelectedKnowledgeBaseId(null);
      return;
    }

    let cancelled = false;
    listChatKnowledgeBases()
      .then((options) => {
        if (cancelled) return;
        const nextOptions = options.length > 0 ? options : [ALL_SOURCES_OPTION];
        setKnowledgeBaseOptions(nextOptions);
        setSelectedKnowledgeBaseId((current) => (
          nextOptions.some((option) => option.knowledgeBaseId === current)
            ? current
            : (nextOptions.find((option) => option.knowledgeBaseId === null)?.knowledgeBaseId ?? null)
        ));
      })
      .catch(() => {
        if (cancelled) return;
        setKnowledgeBaseOptions([ALL_SOURCES_OPTION]);
        setSelectedKnowledgeBaseId(null);
      });

    return () => { cancelled = true; };
  }, [isAuthenticated]);

  // Auto-scroll only when a brand-new message is appended (user submit / new assistant bubble).
  // No scrolling during streaming, citations, or graphRefs updates — let the user read at their own pace.
  useEffect(() => {
    requestAnimationFrame(() => {
      if (scrollerRef.current) {
        scrollerRef.current.scrollTo({ top: scrollerRef.current.scrollHeight, behavior: 'smooth' });
      }
    });
  }, [messages.length]);

  // Load a prior chat from the rail.
  useEffect(() => {
    if (activeChatId === null) return;
    let cancelled = false;
    abortRef.current?.abort();
    setError(null);
    setLoading(false);
    setQuery('');
    setCurrentChatId(activeChatId);
    setMessages([]);
    getChatHistory(activeChatId).then((history) => {
      if (cancelled) return;
      const msgs: ChatMessage[] = history.messages.map((m) => ({
        id: nextMsgId(),
        role: (m.role || '').toUpperCase() === 'USER' ? 'user' : 'assistant',
        content: m.content,
        citations: m.citations ?? [],
        graphRefs: m.graphRefs ?? [],
      }));
      setMessages(msgs);
      hydrateMessageCitationNames(msgs)
        .then((hydrated) => {
          if (!cancelled) setMessages(hydrated);
        })
        .catch(() => {});
    }).catch(() => { });
    return () => { cancelled = true; };
  }, [activeChatId]);

  // "New chat" pressed in the rail.
  useEffect(() => {
    if (resetToken === 0) return;
    abortRef.current?.abort();
    setMessages([]);
    setError(null);
    setLoading(false);
    setQuery('');
    setCurrentChatId(undefined);
  }, [resetToken]);

  function handleOpenGraph(ref: GraphRef) {
    navigate(`/jurisdictions/${ref.jurisdictionCode}/launches/${ref.launchId}`);
  }

  function updateLastAssistant(updater: (m: ChatMessage) => ChatMessage) {
    setMessages((prev) => {
      if (prev.length === 0) return prev;
      const lastIdx = prev.length - 1;
      const last = prev[lastIdx];
      if (last.role !== 'assistant') return prev;
      const copy = prev.slice();
      copy[lastIdx] = updater(last);
      return copy;
    });
  }

  function handleSubmit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const trimmed = query.trim();
    if (!trimmed || loading) return;
    if (!isAuthenticated) {
      showGate(CHAT_GATE);
      return;
    }

    abortRef.current?.abort();
    const controller = new AbortController();
    abortRef.current = controller;
    const requestKnowledgeBase = selectedKnowledgeBase;

    const userMsg: ChatMessage = { id: nextMsgId(), role: 'user', content: trimmed };
    const asstMsg: ChatMessage = {
      id: nextMsgId(),
      role: 'assistant',
      content: '',
      citations: [],
      graphRefs: [],
      knowledgeBaseId: requestKnowledgeBase.knowledgeBaseId,
      knowledgeBaseLabel: requestKnowledgeBase.label,
      pending: true,
    };
    setMessages((prev) => [...prev, userMsg, asstMsg]);
    setQuery('');
    setError(null);
    setLoading(true);

    postChatStream(
      { query: trimmed, chatId: currentChatId, knowledgeBaseId: requestKnowledgeBase.knowledgeBaseId },
      {
        onStarted: (ev) => setCurrentChatId(ev.chatId),
        onDelta: (d) => updateLastAssistant((m) => ({ ...m, content: m.content + d })),
        onCitations: (cits) => {
          updateLastAssistant((m) => ({ ...m, citations: cits }));
          hydrateCitationDocumentNames(cits)
            .then((hydrated) => updateLastAssistant((m) => ({ ...m, citations: hydrated })))
            .catch(() => {});
        },
        onGraphRefs: (refs) => updateLastAssistant((m) => ({ ...m, graphRefs: refs })),
        onCompleted: () => {
          setLoading(false);
          updateLastAssistant((m) => ({
            ...m,
            pending: false,
            graphRefs: USE_MOCK ? matchGraphRefsFromPrompt(trimmed) : m.graphRefs,
          }));
        },
        onFailed: (ev) => {
          setLoading(false);
          if (USE_MOCK) {
            updateLastAssistant((m) => ({
              ...m,
              pending: false,
              content: m.content || 'In NL, the Crypto Debit Card launch has 3 open compliance gaps under DNB Wwft Art 3 and MiCA Art 75 sanctions screening. Two controls are partially covered; a real-time OFAC screening step is missing from ToC §5.3.',
              graphRefs: matchGraphRefsFromPrompt(trimmed),
            }));
          } else {
            setError(ev.message);
            // Drop the empty pending assistant message so the transcript isn't left with a blank bubble.
            setMessages((prev) => {
              if (prev.length === 0) return prev;
              const last = prev[prev.length - 1];
              if (last.role === 'assistant' && last.pending && !last.content) {
                return prev.slice(0, -1);
              }
              return prev;
            });
          }
        },
      },
      controller.signal,
    );
  }

  function handleReset() {
    abortRef.current?.abort();
    setMessages([]);
    setError(null);
    setLoading(false);
    setQuery('');
    setCurrentChatId(undefined);
  }

  const hasConversation = messages.length > 0;
  const showHero = !hasConversation && !loading && !error;

  // ── EMPTY STATE: hero + centered search ─────────────────────────────────────
  if (!hasConversation) {
    return (
      <div
        className="relative flex w-full px-4 md:px-6 items-center justify-center"
        style={{ minHeight: '100%' }}
      >
        <PrismCanvas />
        <div
          className="relative w-full text-center"
          style={{ zIndex: 10, maxWidth: '1100px', margin: '0 auto' }}
        >
          {showHero && <CategoryRow />}
          {showHero && (
            <>
              <h1
                className="font-serif font-normal text-white leading-[0.95] tracking-tight"
                style={{
                  fontSize: 'clamp(40px, 9vw, 124px)',
                  pointerEvents: 'none',
                  userSelect: 'none',
                  WebkitUserSelect: 'none',
                  opacity: 0.95,
                  paddingBottom: '0.35em',
                  WebkitMaskImage: 'linear-gradient(to bottom, black 30%, transparent 100%)',
                  maskImage: 'linear-gradient(to bottom, black 30%, transparent 100%)',
                }}
              >
                Split every policy
              </h1>
              <h1
                className="font-serif font-normal italic text-white leading-[0.95] tracking-tight"
                style={{
                  fontSize: 'clamp(40px, 9vw, 124px)',
                  pointerEvents: 'none',
                  userSelect: 'none',
                  WebkitUserSelect: 'none',
                  opacity: 0.95,
                  paddingBottom: '0.35em',
                  marginTop: 'calc(0.25rem - 0.35em)',
                  WebkitMaskImage: 'linear-gradient(to bottom, black 30%, transparent 100%)',
                  maskImage: 'linear-gradient(to bottom, black 30%, transparent 100%)',
                }}
              >
                into its colours<span className="not-italic" style={{ color: '#ef6a2a' }}>.</span>
              </h1>
            </>
          )}

          <div className="w-full flex flex-col items-center mt-2">
            <KnowledgeBaseSelector
              options={knowledgeBaseOptions}
              selectedId={selectedKnowledgeBaseId}
              onChange={setSelectedKnowledgeBaseId}
              disabled={loading}
            />
            <SearchBar
              query={query}
              setQuery={setQuery}
              onSubmit={handleSubmit}
              disabled={loading}
            />
            {showHero && selectedKnowledgeBase.knowledgeBaseId === null && (
              <p className="mt-3 text-[12px] text-white/35">
                Currently searching all sources. Use the selector above to narrow.
              </p>
            )}
          </div>

          {showHero && <TryAsking onSelect={(q) => setQuery(q)} />}

          {loading && (
            <div className="mt-8 text-white/50 text-[14px] font-mono tracking-wider animate-pulse">
              Searching…
            </div>
          )}

          {error && (
            <div
              className="mt-8 rounded-xl px-5 py-4 text-left"
              style={{
                background: 'rgba(224,80,80,0.1)',
                border: '1px solid rgba(224,80,80,0.3)',
                maxWidth: 820,
                margin: '32px auto 0',
              }}
            >
              <p className="text-[13px]" style={{ color: '#E05050' }}>{error}</p>
              <button onClick={handleReset} className="mt-3 text-[12px] font-medium" style={{ color: '#FF9F55' }}>
                Try again →
              </button>
            </div>
          )}
        </div>
        {judgesModal}
      </div>
    );
  }

  // ── CONVERSATION STATE: scrollable transcript + sticky input ───────────────
  return (
    <div
      className="relative flex flex-col w-full"
      style={{ height: '100%' }}
    >
      <PrismCanvas />

      {/* Top bar: New chat */}
      <div
        className="relative shrink-0 flex justify-end px-6 py-3"
        style={{ zIndex: 10 }}
      >
        <button
          onClick={handleReset}
          className="text-[12px] font-mono text-white/40 hover:text-white/80 transition uppercase tracking-[0.12em]"
        >
          + New chat
        </button>
      </div>

      {/* Transcript */}
      <div
        ref={scrollerRef}
        className="relative flex-1 min-h-0 overflow-y-auto px-4 md:px-6"
        style={{ zIndex: 10 }}
      >
        <div className="w-full mx-auto flex flex-col gap-4 pb-6" style={{ maxWidth: 820 }}>
          {messages.map((m) => (
            <ChatBubble
              key={m.id}
              message={m}
              loading={loading && m.role === 'assistant' && m.pending === true}
              onOpenGraph={handleOpenGraph}
            />
          ))}
          {error && (
            <div
              className="rounded-xl px-5 py-4 text-left"
              style={{
                background: 'rgba(224,80,80,0.1)',
                border: '1px solid rgba(224,80,80,0.3)',
              }}
            >
              <p className="text-[13px]" style={{ color: '#E05050' }}>{error}</p>
            </div>
          )}
        </div>
      </div>

      {/* Input — pinned at bottom */}
      <div
        className="relative shrink-0 px-4 md:px-6 pt-3 pb-4"
        style={{
          zIndex: 10,
          background: 'linear-gradient(to top, rgba(8,8,10,0.95) 60%, rgba(8,8,10,0))',
        }}
      >
        <KnowledgeBaseSelector
          options={knowledgeBaseOptions}
          selectedId={selectedKnowledgeBaseId}
          onChange={setSelectedKnowledgeBaseId}
          disabled={loading}
        />
        <SearchBar
          query={query}
          setQuery={setQuery}
          onSubmit={handleSubmit}
          disabled={loading}
          placeholder="Ask a follow-up…"
        />
      </div>
      {judgesModal}
    </div>
  );
}

// ─── Chat bubble ──────────────────────────────────────────────────────────────

function citationKbLabel(citation: Citation) {
  return citation.knowledgeBaseLabel || citation.kbType || 'Source';
}

function groupCitations(citations: Citation[]) {
  const groups: { label: string; citations: Citation[] }[] = [];
  for (const citation of citations) {
    const label = citationKbLabel(citation);
    const group = groups.find((item) => item.label === label);
    if (group) {
      group.citations.push(citation);
    } else {
      groups.push({ label, citations: [citation] });
    }
  }
  return groups;
}

function sourceDisplayName(citation: Citation) {
  const explicit = citation.displayName?.trim();
  if (explicit) return explicit;

  const fileName = citation.s3Uri ? citationFileName(citation.s3Uri) : '';
  if (/^[a-f0-9]{64}\.[a-z0-9]+$/i.test(fileName)) {
    return 'Uploaded document';
  }
  return fileName || 'Source document';
}

function citationDocumentId(citation: Citation) {
  const explicit = citation.docId ?? citation.documentId ?? citation.sha256;
  if (explicit) return explicit;
  const source = `${citation.s3Uri ?? ''} ${citation.chunkId ?? ''}`;
  const match = source.match(/\/documents\/([a-f0-9]{64})(?:\.[^/#\s]+)?/i);
  return match?.[1]?.toLowerCase() ?? null;
}

function truncHash(value: string) {
  const cleaned = value.includes('#') ? value.slice(value.lastIndexOf('#') + 1) : value;
  return cleaned.length > 12 ? `${cleaned.slice(0, 6)}...${cleaned.slice(-4)}` : cleaned;
}

function chunkLabel(citation: Citation) {
  if (!citation.chunkId) return null;
  return `chunk ${truncHash(citation.chunkId)}`;
}

function resolveCitationDocumentName(documentId: string): Promise<string | null> {
  const cached = citationDocumentNameCache.get(documentId);
  if (cached) return Promise.resolve(cached);

  const inFlight = citationDocumentFetches.get(documentId);
  if (inFlight) return inFlight;

  const request = getLibraryDocument(documentId)
    .then((doc) => {
      const name = (doc.displayName || doc.filename || '').trim() || null;
      if (name) citationDocumentNameCache.set(documentId, name);
      return name;
    })
    .catch(() => null)
    .finally(() => {
      citationDocumentFetches.delete(documentId);
    });

  citationDocumentFetches.set(documentId, request);
  return request;
}

async function hydrateCitationDocumentNames(citations: Citation[]): Promise<Citation[]> {
  const hydrated = await Promise.all(citations.map(async (citation) => {
    if (citation.displayName?.trim()) {
      return citation;
    }

    const documentId = citationDocumentId(citation);
    if (!documentId) {
      return citation;
    }

    const name = await resolveCitationDocumentName(documentId);
    return name ? { ...citation, documentId, sha256: citation.sha256 ?? documentId, displayName: name } : citation;
  }));

  return hydrated;
}

interface ChatBubbleProps {
  message: ChatMessage;
  loading: boolean;
  onOpenGraph: (ref: GraphRef) => void;
}

interface CitationDisclosureProps {
  citation: Citation;
  initiallyOpen: boolean;
}

function CitationDisclosure({ citation, initiallyOpen }: CitationDisclosureProps) {
  const targetDocId = citationDocumentId(citation);
  const cardHref = targetDocId ? `/doc/${targetDocId}` : undefined;
  const sourceName = sourceDisplayName(citation);
  const chunk = chunkLabel(citation);
  const sha = citation.sha256 ? `sha ${truncHash(citation.sha256)}` : null;
  const score = typeof citation.score === 'number' && citation.score > 0 ? citation.score.toFixed(2) : null;

  return (
    <details
      open={initiallyOpen}
      className="group rounded-lg overflow-hidden"
      style={{
        background: 'rgba(255,255,255,0.025)',
        border: '1px solid rgba(255,255,255,0.07)',
      }}
    >
      <summary className="flex cursor-pointer list-none items-center gap-3 px-3 py-2.5 transition-colors hover:bg-white/[0.03] [&::-webkit-details-marker]:hidden">
        <span
          className="flex h-5 w-5 shrink-0 items-center justify-center rounded-full text-[11px] transition-transform group-open:rotate-90"
          style={{ color: 'rgba(255,255,255,0.36)', border: '1px solid rgba(255,255,255,0.10)' }}
        >
          &gt;
        </span>
        <div className="min-w-0 flex-1">
          <div className="flex min-w-0 items-center gap-2">
            <span
              className="shrink-0 text-[10px] font-mono rounded-full px-2 py-0.5"
              style={{
                background: `${KB_COLORS[citation.kbType] ?? '#888'}22`,
                color: KB_COLORS[citation.kbType] ?? '#888',
                border: `1px solid ${KB_COLORS[citation.kbType] ?? '#888'}44`,
              }}
            >
              {citationKbLabel(citation)}
            </span>
            <span className="truncate text-[12px] font-medium text-white/75" title={sourceName}>
              {sourceName}
            </span>
          </div>
          {(chunk || sha || score) && (
            <p className="mt-0.5 truncate font-mono text-[10px]" style={{ color: 'rgba(255,255,255,0.28)' }}>
              {[chunk, sha, score ? `score ${score}` : null].filter(Boolean).join(' / ')}
            </p>
          )}
        </div>
      </summary>
      <div className="px-3 pb-3">
        <p className="text-[11px] leading-relaxed" style={{ color: 'rgba(255,255,255,0.48)' }}>
          {citation.sourceText}
        </p>
        {cardHref && (
          <Link
            to={cardHref}
            className="mt-2 inline-flex text-[11px] font-medium text-orange-300 hover:text-orange-200"
          >
            Open document
          </Link>
        )}
      </div>
    </details>
  );
}

function ChatBubble({ message, loading, onOpenGraph }: ChatBubbleProps) {
  const isUser = message.role === 'user';

  if (isUser) {
    return (
      <div className="self-end max-w-[85%]">
        <div
          className="rounded-2xl px-4 py-3 text-left text-white text-[14px] leading-relaxed"
          style={{
            background: 'rgba(239,106,42,0.16)',
            border: '1px solid rgba(239,106,42,0.32)',
          }}
        >
          {message.content}
        </div>
      </div>
    );
  }

  // Assistant
  return (
    <div className="self-start w-full">
      <div
        className="rounded-2xl text-left"
        style={{
          background: 'rgba(20,20,20,0.85)',
          border: '1px solid rgba(255,255,255,0.08)',
          backdropFilter: 'blur(16px)',
        }}
      >
        <div className="px-5 py-4">
          {message.content ? (
            <div className="text-white/85 text-[14px] leading-relaxed">
              <ReactMarkdown
                remarkPlugins={[remarkGfm]}
                components={{
                  h1: ({ children }) => <h1 className="text-white text-[20px] font-semibold mt-4 mb-2 first:mt-0">{children}</h1>,
                  h2: ({ children }) => <h2 className="text-white text-[17px] font-semibold mt-4 mb-2 first:mt-0">{children}</h2>,
                  h3: ({ children }) => <h3 className="text-white text-[15px] font-semibold mt-3 mb-1.5 first:mt-0">{children}</h3>,
                  p: ({ children }) => <p className="text-white/85 text-[14px] leading-relaxed my-2 first:mt-0 last:mb-0">{children}</p>,
                  ul: ({ children }) => <ul className="list-disc pl-5 my-2 space-y-1">{children}</ul>,
                  ol: ({ children }) => <ol className="list-decimal pl-5 my-2 space-y-1">{children}</ol>,
                  li: ({ children }) => <li className="text-white/85 text-[14px] leading-relaxed">{children}</li>,
                  strong: ({ children }) => <strong className="text-white font-semibold">{children}</strong>,
                  em: ({ children }) => <em className="italic">{children}</em>,
                  a: ({ children, href }) => <a className="text-orange-400 hover:text-orange-300 underline underline-offset-2" href={href} target="_blank" rel="noreferrer">{children}</a>,
                  code: ({ className, children }) => {
                    const isBlock = className?.startsWith('language-');
                    if (isBlock) {
                      return (
                        <pre className="my-2 p-3 rounded-lg bg-black/40 border border-white/10 overflow-x-auto">
                          <code className="text-[12px] font-mono text-white/85">{children}</code>
                        </pre>
                      );
                    }
                    return <code className="px-1 py-0.5 rounded bg-white/10 text-orange-300 text-[12px] font-mono">{children}</code>;
                  },
                  hr: () => <hr className="my-3 border-white/10" />,
                  blockquote: ({ children }) => <blockquote className="border-l-2 border-orange-400/50 pl-3 my-2 text-white/70 italic">{children}</blockquote>,
                }}
              >
                {message.content}
              </ReactMarkdown>
              {loading && (
                <span className="inline-block w-[2px] h-[14px] ml-[2px] bg-orange-400 animate-pulse align-middle" />
              )}
            </div>
          ) : (
            <p className="text-white/30 text-[13px] font-mono animate-pulse m-0">Generating…</p>
          )}
        </div>

        {message.graphRefs && message.graphRefs.length > 0 && (
          <div
            className="px-5 pb-4"
            style={{ borderTop: '1px solid rgba(255,255,255,0.06)', paddingTop: '12px' }}
          >
            <GraphRefChips refs={message.graphRefs} onOpen={onOpenGraph} />
          </div>
        )}

        {message.citations && message.citations.length > 0 && (
          <div
            className="px-5 py-4"
            style={{ borderTop: '1px solid rgba(255,255,255,0.06)' }}
          >
            <p className="text-[10px] font-bold uppercase tracking-[0.12em] mb-3" style={{ color: 'rgba(255,255,255,0.3)' }}>
              {message.knowledgeBaseId
                ? `Sources from ${message.knowledgeBaseLabel ?? 'selected source'} (${message.citations.length})`
                : `Sources (${message.citations.length})`}
            </p>
            <div className="flex flex-col gap-3">
              {(message.knowledgeBaseId
                ? [{ label: message.knowledgeBaseLabel ?? 'Sources', citations: message.citations }]
                : groupCitations(message.citations)
              ).map((group, groupIndex) => (
                <div key={group.label} className="flex flex-col gap-2">
                  {!message.knowledgeBaseId && (
                    <div className="flex items-center justify-between gap-3">
                      <p className="text-[11px] font-semibold" style={{ color: 'rgba(255,255,255,0.62)' }}>
                        {group.label}
                      </p>
                      <span className="text-[10px] font-mono" style={{ color: 'rgba(255,255,255,0.28)' }}>
                        {group.citations.length}
                      </span>
                    </div>
                  )}
                  {group.citations.map((c, i) => (
                    <CitationDisclosure
                      key={c.chunkId || `${group.label}-${i}`}
                      citation={c}
                      initiallyOpen={groupIndex === 0 && i === 0}
                    />
                  ))}
                </div>
              ))}
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
