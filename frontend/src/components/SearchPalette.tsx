import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { searchAll } from '../api/search';
import type { SearchHit, SearchResponse } from '../api/search';

interface Props {
  open: boolean;
  onClose: () => void;
}

const NAVIGABLE = new Set(['launch', 'jurisdiction']);

function hitPath(hit: SearchHit): string | null {
  if (hit.type === 'launch') return `/launches/${hit.id}`;
  if (hit.type === 'jurisdiction') return `/jurisdictions/${hit.id}`;
  return null;
}

const GROUP_ORDER: Array<{ key: keyof Omit<SearchResponse, 'query'>; label: string }> = [
  { key: 'launches', label: 'Launches' },
  { key: 'jurisdictions', label: 'Jurisdictions' },
  { key: 'documents', label: 'Documents' },
  { key: 'sessions', label: 'Sessions' },
  { key: 'obligations', label: 'Obligations' },
  { key: 'controls', label: 'Controls' },
];

export default function SearchPalette({ open, onClose }: Props) {
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<SearchResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);
  const reqIdRef = useRef(0);
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const navigate = useNavigate();

  useEffect(() => {
    if (open) {
      setQuery('');
      setResults(null);
      setTimeout(() => inputRef.current?.focus(), 0);
    }
  }, [open]);

  useEffect(() => {
    if (!open) return;
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    document.addEventListener('keydown', handler);
    return () => document.removeEventListener('keydown', handler);
  }, [open, onClose]);

  useEffect(() => {
    if (timerRef.current) clearTimeout(timerRef.current);
    if (query.length < 2) {
      setResults(null);
      setLoading(false);
      return;
    }
    setLoading(true);
    const id = ++reqIdRef.current;
    timerRef.current = setTimeout(() => {
      searchAll(query, 5)
        .then((res) => {
          if (reqIdRef.current === id) {
            setResults(res);
            setLoading(false);
          }
        })
        .catch(() => {
          if (reqIdRef.current === id) setLoading(false);
        });
    }, 250);
    return () => {
      if (timerRef.current) clearTimeout(timerRef.current);
    };
  }, [query]);

  if (!open) return null;

  const hasResults = results && GROUP_ORDER.some((g) => results[g.key].length > 0);

  return (
    <div
      style={{
        position: 'fixed',
        inset: 0,
        zIndex: 1000,
        display: 'flex',
        alignItems: 'flex-start',
        justifyContent: 'center',
        paddingTop: '80px',
        background: 'rgba(8, 8, 8, 0.75)',
        backdropFilter: 'blur(6px)',
      }}
      onClick={onClose}
    >
      <div
        style={{
          width: '560px',
          maxWidth: 'calc(100vw - 32px)',
          background: 'var(--bg-1, #111)',
          border: '1px solid var(--line-1, rgba(246,241,234,0.1))',
          borderRadius: '10px',
          overflow: 'hidden',
          boxShadow: '0 24px 64px -12px rgba(0,0,0,0.7)',
        }}
        onClick={(e) => e.stopPropagation()}
      >
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: '10px',
            padding: '12px 16px',
            borderBottom: '1px solid var(--line-1, rgba(246,241,234,0.08))',
          }}
        >
          <svg width="14" height="14" viewBox="0 0 14 14" fill="none" style={{ flexShrink: 0, color: 'var(--ink-2, #888)' }}>
            <circle cx="6" cy="6" r="4.5" stroke="currentColor" strokeWidth="1.3" />
            <path d="M9.5 9.5L12 12" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round" />
          </svg>
          <input
            ref={inputRef}
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="Search launches, jurisdictions…"
            style={{
              flex: 1,
              background: 'transparent',
              border: 'none',
              outline: 'none',
              color: 'var(--ink-0, #fff)',
              fontSize: '14px',
              fontFamily: 'inherit',
            }}
          />
          {loading && (
            <span style={{ fontSize: '11px', color: 'var(--ink-2, #888)' }}>Searching…</span>
          )}
        </div>

        {query.length >= 2 && !loading && !hasResults && (
          <div style={{ padding: '24px 16px', textAlign: 'center', color: 'var(--ink-2, #888)', fontSize: '13px' }}>
            No results
          </div>
        )}

        {hasResults && (
          <div style={{ maxHeight: '400px', overflowY: 'auto' }}>
            {GROUP_ORDER.map(({ key, label }) => {
              const hits = results![key];
              if (!hits.length) return null;
              return (
                <div key={key}>
                  <div
                    style={{
                      padding: '8px 16px 4px',
                      fontSize: '10px',
                      fontWeight: 600,
                      letterSpacing: '0.08em',
                      textTransform: 'uppercase',
                      color: 'var(--ink-2, #888)',
                    }}
                  >
                    {label}
                  </div>
                  {hits.map((hit) => {
                    const path = hitPath(hit);
                    const clickable = NAVIGABLE.has(hit.type) && path !== null;
                    return (
                      <div
                        key={`${hit.type}-${hit.id}`}
                        onClick={clickable ? () => { navigate(path!); onClose(); } : undefined}
                        style={{
                          display: 'flex',
                          alignItems: 'center',
                          gap: '10px',
                          padding: '8px 16px',
                          cursor: clickable ? 'pointer' : 'default',
                          opacity: clickable ? 1 : 0.5,
                          transition: clickable ? 'background 120ms' : undefined,
                        }}
                        onMouseEnter={clickable ? (e) => { (e.currentTarget as HTMLDivElement).style.background = 'var(--bg-2, rgba(246,241,234,0.05))'; } : undefined}
                        onMouseLeave={clickable ? (e) => { (e.currentTarget as HTMLDivElement).style.background = 'transparent'; } : undefined}
                      >
                        <div style={{ flex: 1, minWidth: 0 }}>
                          <div style={{ fontSize: '13px', fontWeight: 500, color: 'var(--ink-0, #fff)', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
                            {hit.title}
                          </div>
                          {hit.subtitle && (
                            <div style={{ fontSize: '11px', color: 'var(--ink-2, #888)', marginTop: '1px', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
                              {hit.subtitle}
                            </div>
                          )}
                        </div>
                        <span
                          style={{
                            fontSize: '9px',
                            fontWeight: 600,
                            letterSpacing: '0.06em',
                            textTransform: 'uppercase',
                            padding: '2px 6px',
                            borderRadius: '4px',
                            background: 'var(--bg-2, rgba(246,241,234,0.06))',
                            color: 'var(--ink-2, #888)',
                            flexShrink: 0,
                          }}
                        >
                          {hit.type}
                        </span>
                      </div>
                    );
                  })}
                </div>
              );
            })}
          </div>
        )}
      </div>
    </div>
  );
}
