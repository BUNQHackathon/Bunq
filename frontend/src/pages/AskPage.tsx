import { useState } from 'react';
import PrismCanvas from '../components/PrismCanvas';

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
          disabled={disabled}
          className="w-10 h-10 rounded-full flex items-center justify-center shrink-0 transition hover:brightness-110"
          style={{ background: '#FF7819', opacity: disabled ? 0.5 : 1 }}
          aria-label="Send"
        >
          <IconSend disabled={disabled} />
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
          className="rounded-pill border border-white/[0.12] px-4 py-2 text-[13px] text-white/80 hover:bg-white/5 hover:border-white/25 transition bg-transparent"
        >
          {q}
        </button>
      ))}
    </div>
  );
}

// ─── Page ─────────────────────────────────────────────────────────────────────

export default function AskPage() {
  const [query, setQuery] = useState('');

  function handleSubmit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    // no-op for now — visual-only
  }

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
        <CategoryRow />
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

        <div className="w-full flex flex-col items-center mt-12">
          <SearchBar
            query={query}
            setQuery={setQuery}
            onSubmit={handleSubmit}
            disabled={false}
          />
        </div>

        <TryAsking onSelect={(q) => setQuery(q)} />
      </div>
    </div>
  );
}
