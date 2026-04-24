import { useRef, useState } from 'react';
import PrismCanvas from '../components/PrismCanvas';
import { postChatStream, citationFileName, type Citation } from '../api/chat';

// ─── Local data ───────────────────────────────────────────────────────────────

const suggestedQuestions = [
  'What are our AML obligations in Germany?',
  'Can we offer crypto custody under MiCA?',
  'GDPR retention rules for transaction data',
  'EMI passporting requirements for France',
];

// ─── Local icon components ────────────────────────────────────────────────────

function IconPaperclip() {
  return (
    <svg width="16" height="16" viewBox="0 0 16 16" fill="none">
      <path
        d="M13.5 7.5L7 14a4.243 4.243 0 0 1-6-6l7-7a2.828 2.828 0 0 1 4 4L5.5 12a1.414 1.414 0 0 1-2-2L10 3.5"
        stroke="currentColor"
        strokeWidth="1.3"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  );
}

function IconFilter() {
  return (
    <svg width="16" height="16" viewBox="0 0 16 16" fill="none">
      <path
        d="M2 4h12M4.5 8h7M7 12h2"
        stroke="currentColor"
        strokeWidth="1.3"
        strokeLinecap="round"
      />
    </svg>
  );
}

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
  { label: 'PRIVACY', color: '#B08AFF' },
  { label: 'AML', color: '#FF9F55' },
  { label: 'LICENSING', color: '#A8D66C' },
  { label: 'TERMS', color: '#5ECFA0' },
  { label: 'SANCTIONS', color: '#6EB7E8' },
  { label: 'REPORTS', color: '#E05050' },
];

function CategoryRow() {
  return (
    <div className="flex flex-wrap items-center justify-center gap-x-4 gap-y-2 md:gap-7 mb-5 px-2">
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
}

function SearchBar({ query, setQuery, onSubmit, disabled }: SearchBarProps) {
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
        {/* Chips */}
        <div className="hidden sm:flex items-center gap-1.5 shrink-0">
          <span
            className="flex items-center gap-1 rounded-pill px-2.5 py-1 text-[12px] text-white/80"
            style={{ background: '#1F1F1F' }}
          >
            🇳🇱 NL
          </span>
          <span
            className="flex items-center gap-1 rounded-pill px-2.5 py-1 text-[12px] text-white/80"
            style={{ background: '#1F1F1F' }}
          >
            AML
          </span>
          <button
            type="button"
            className="text-white/35 hover:text-white/60 transition text-[13px] leading-none px-1"
            aria-label="Clear chips"
          >
            ×
          </button>
          <div
            className="w-px h-4 mx-1"
            style={{ background: 'rgba(255,255,255,0.1)' }}
          />
        </div>

        {/* Input */}
        <input
          type="text"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="Ask about bunq's compliance across the EU…"
          className="flex-1 bg-transparent outline-none text-white text-[15px] min-w-0"
          style={{ caretColor: '#FF7819' }}
          disabled={disabled}
        />

        {/* Paperclip */}
        <button
          type="button"
          className="hidden sm:block text-white/35 hover:text-white/60 transition shrink-0"
          aria-label="Attach file"
        >
          <IconPaperclip />
        </button>

        {/* Filter */}
        <button
          type="button"
          className="hidden sm:block text-white/35 hover:text-white/60 transition shrink-0"
          aria-label="Filter"
        >
          <IconFilter />
        </button>

        {/* Send */}
        <button
          type="submit"
          disabled={disabled || !query.trim()}
          className="w-10 h-10 rounded-full flex items-center justify-center shrink-0 transition hover:brightness-110"
          style={{ background: '#FF7819', opacity: (disabled || !query.trim()) ? 0.5 : 1 }}
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
      <span className="font-mono uppercase tracking-[0.2em] text-[11px] text-white/40 shrink-0">
        TRY ASKING
      </span>
      {suggestedQuestions.map((q) => (
        <button
          key={q}
          type="button"
          onClick={() => onSelect(q)}
          className="rounded-pill border px-4 py-2 text-[13px] text-white/80 transition hover:text-white"
          style={{
            background: 'rgba(14,10,8,0.65)',
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

// ─── Page ─────────────────────────────────────────────────────────────────────

export default function AskPage() {
  const [query, setQuery] = useState('');
  const [submittedQuery, setSubmittedQuery] = useState<string | null>(null);
  const [answer, setAnswer] = useState<string | null>(null);
  const [citations, setCitations] = useState<Citation[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [currentChatId, setCurrentChatId] = useState<string | undefined>(undefined);
  const abortRef = useRef<AbortController | null>(null);

  function handleSubmit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const trimmed = query.trim();
    if (!trimmed || loading) return;

    // abort any in-flight request
    abortRef.current?.abort();
    const controller = new AbortController();
    abortRef.current = controller;

    setSubmittedQuery(trimmed);
    setAnswer(null);
    setCitations([]);
    setError(null);
    setLoading(true);

    postChatStream(
      { query: trimmed, chatId: currentChatId },
      {
        onStarted: (ev) => setCurrentChatId(ev.chatId),
        onDelta: (d) => setAnswer((prev) => (prev ?? '') + d),
        onCitations: setCitations,
        onCompleted: () => setLoading(false),
        onFailed: (ev) => {
          setError(ev.message);
          setLoading(false);
        },
      },
      controller.signal,
    );
  }

  function handleReset() {
    abortRef.current?.abort();
    setSubmittedQuery(null);
    setAnswer(null);
    setCitations([]);
    setError(null);
    setLoading(false);
    setQuery('');
  }

  const showHero = !submittedQuery && !loading && !error;

  return (
    <div
      className="relative flex w-full px-4 md:px-6 items-center justify-center"
      style={{ minHeight: '100vh' }}
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
              into its colours<span className="text-prism-orange not-italic">.</span>
            </h1>
            <p className="text-white/55 text-[15px] mt-6">
              One prompt. Cross-jurisdictional answers, always backed by source.
            </p>
          </>
        )}

        <div className={`w-full flex flex-col items-center ${showHero ? 'mt-12' : 'mt-8'}`}>
          <SearchBar
            query={query}
            setQuery={setQuery}
            onSubmit={handleSubmit}
            disabled={loading}
          />
        </div>

        {showHero && <TryAsking onSelect={(q) => setQuery(q)} />}

        {/* Loading indicator */}
        {loading && !answer && (
          <div className="mt-8 text-white/50 text-[14px] font-mono tracking-wider animate-pulse">
            Searching…
          </div>
        )}

        {/* Error block */}
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
            <button
              onClick={handleReset}
              className="mt-3 text-[12px] font-medium"
              style={{ color: '#FF9F55' }}
            >
              Try again →
            </button>
          </div>
        )}

        {/* Answer block */}
        {submittedQuery && (answer !== null || loading) && !error && (
          <div
            className="mt-8 rounded-xl text-left"
            style={{
              background: 'rgba(20,20,20,0.85)',
              border: '1px solid rgba(255,255,255,0.08)',
              backdropFilter: 'blur(16px)',
              maxWidth: 820,
              margin: '32px auto 0',
            }}
          >
            {/* Question header */}
            <div
              className="px-5 py-4 flex items-start justify-between gap-4"
              style={{ borderBottom: '1px solid rgba(255,255,255,0.06)' }}
            >
              <p className="text-white/90 text-[14px] font-medium leading-relaxed flex-1">
                {submittedQuery}
              </p>
              <button
                onClick={handleReset}
                className="shrink-0 text-[11px] font-mono text-white/30 hover:text-white/60 transition mt-0.5"
              >
                ✕ Reset
              </button>
            </div>

            {/* Streaming answer */}
            <div className="px-5 py-4">
              {answer ? (
                <pre
                  className="text-white/80 text-[14px] leading-relaxed whitespace-pre-wrap font-sans"
                >
                  {answer}
                  {loading && (
                    <span className="inline-block w-[2px] h-[14px] ml-[2px] bg-orange-400 animate-pulse align-middle" />
                  )}
                </pre>
              ) : (
                <p className="text-white/30 text-[13px] font-mono animate-pulse">Generating…</p>
              )}
            </div>

            {/* Citations */}
            {citations.length > 0 && (
              <div
                className="px-5 py-4"
                style={{ borderTop: '1px solid rgba(255,255,255,0.06)' }}
              >
                <p className="text-[10px] font-bold uppercase tracking-[0.12em] mb-3" style={{ color: 'rgba(255,255,255,0.3)' }}>
                  Sources ({citations.length})
                </p>
                <div className="flex flex-col gap-2">
                  {citations.map((c, i) => (
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
                          {citationFileName(c.s3Uri)}
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
        )}
      </div>
    </div>
  );
}
