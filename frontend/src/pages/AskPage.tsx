import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import PrismCanvas from '../components/PrismCanvas';
import { postChatStream, citationFileName, getChatHistory, type Citation, type GraphRef } from '../api/chat';
import GraphRefChips from '../components/GraphRefChips';
import { matchGraphRefsFromPrompt } from '../api/mock';
import { USE_MOCK } from '../api/launch';
import { useChatNav } from '../lib/chatNav';

// ─── Local data ───────────────────────────────────────────────────────────────

const suggestedQuestions = [
  'What are our AML obligations in Germany?',
  'Can we offer crypto custody under MiCA?',
  'GDPR retention rules for transaction data',
  'EMI passporting requirements for France',
];

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
        fill="white"
        stroke="white"
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
    <div className="ask__categories flex flex-nowrap sm:flex-wrap sm:justify-center items-center gap-x-4 gap-y-2 md:gap-7 mb-5 px-2 overflow-x-auto" style={{ scrollbarWidth: 'none' }}>
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
          className="w-10 h-10 rounded-full flex items-center justify-center shrink-0 transition hover:brightness-110"
          style={{ background: '#ef6a2a', opacity: (disabled || !query.trim()) ? 0.5 : 1 }}
          aria-label="Send"
        >
          <IconSend disabled={disabled || !query.trim()} />
        </button>
      </div>
    </form>
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
  pending?: boolean;
};

let _msgSeq = 0;
function nextMsgId() {
  _msgSeq += 1;
  return `m${Date.now()}-${_msgSeq}`;
}

// ─── Page ─────────────────────────────────────────────────────────────────────

export default function AskPage() {
  const navigate = useNavigate();
  const [query, setQuery] = useState('');
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [currentChatId, setCurrentChatId] = useState<string | undefined>(undefined);
  const abortRef = useRef<AbortController | null>(null);
  const transcriptEndRef = useRef<HTMLDivElement>(null);

  const { activeChatId, resetToken } = useChatNav();

  // Auto-scroll to latest message whenever the transcript grows.
  useEffect(() => {
    transcriptEndRef.current?.scrollIntoView({ behavior: 'smooth', block: 'end' });
  }, [messages.length, messages[messages.length - 1]?.content]);

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
      }));
      setMessages(msgs);
    }).catch(() => {});
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

    abortRef.current?.abort();
    const controller = new AbortController();
    abortRef.current = controller;

    const userMsg: ChatMessage = { id: nextMsgId(), role: 'user', content: trimmed };
    const asstMsg: ChatMessage = {
      id: nextMsgId(),
      role: 'assistant',
      content: '',
      citations: [],
      graphRefs: [],
      pending: true,
    };
    setMessages((prev) => [...prev, userMsg, asstMsg]);
    setQuery('');
    setError(null);
    setLoading(true);

    postChatStream(
      { query: trimmed, chatId: currentChatId },
      {
        onStarted: (ev) => setCurrentChatId(ev.chatId),
        onDelta: (d) => updateLastAssistant((m) => ({ ...m, content: m.content + d })),
        onCitations: (cits) => updateLastAssistant((m) => ({ ...m, citations: cits })),
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
                style={{ fontSize: 'clamp(40px, 9vw, 124px)' }}
              >
                Split every policy
              </h1>
              <h1
                className="font-serif font-normal italic text-white leading-[0.95] tracking-tight mt-1"
                style={{ fontSize: 'clamp(40px, 9vw, 124px)' }}
              >
                into its colours<span className="not-italic" style={{ color: '#ef6a2a' }}>.</span>
              </h1>
              <p className="text-white/55 text-[15px] mt-6">
                One prompt. Cross-jurisdictional answers, always backed by source.
              </p>
            </>
          )}

          <div className="w-full flex flex-col items-center mt-12">
            <SearchBar
              query={query}
              setQuery={setQuery}
              onSubmit={handleSubmit}
              disabled={loading}
            />
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
          <div ref={transcriptEndRef} />
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
        <SearchBar
          query={query}
          setQuery={setQuery}
          onSubmit={handleSubmit}
          disabled={loading}
          placeholder="Ask a follow-up…"
        />
      </div>
    </div>
  );
}

// ─── Chat bubble ──────────────────────────────────────────────────────────────

interface ChatBubbleProps {
  message: ChatMessage;
  loading: boolean;
  onOpenGraph: (ref: GraphRef) => void;
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
            <pre className="text-white/85 text-[14px] leading-relaxed whitespace-pre-wrap font-sans m-0">
              {message.content}
              {loading && (
                <span className="inline-block w-[2px] h-[14px] ml-[2px] bg-orange-400 animate-pulse align-middle" />
              )}
            </pre>
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
              Sources ({message.citations.length})
            </p>
            <div className="flex flex-col gap-2">
              {message.citations.map((c, i) => (
                <div
                  key={c.chunkId || i}
                  className="rounded-lg px-3 py-2.5"
                  style={{
                    background: '#141414',
                    border: '1px solid rgba(255,255,255,0.06)',
                  }}
                >
                  <div className="flex items-center gap-2 mb-1">
                    <span
                      className="text-[10px] font-mono rounded-full px-2 py-0.5"
                      style={{
                        background: `${KB_COLORS[c.kbType] ?? '#888'}22`,
                        color: KB_COLORS[c.kbType] ?? '#888',
                        border: `1px solid ${KB_COLORS[c.kbType] ?? '#888'}44`,
                      }}
                    >
                      {c.kbType}
                    </span>
                    <span className="text-[12px] text-white/70 font-medium truncate">
                      {c.displayName ?? citationFileName(c.s3Uri)}
                    </span>
                  </div>
                  <p
                    className="text-[11px] leading-relaxed line-clamp-2"
                    style={{ color: 'rgba(255,255,255,0.4)' }}
                  >
                    {c.sourceText}
                  </p>
                </div>
              ))}
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
