import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { searchAll } from '../api/search';
import type { SearchResponse } from '../api/search';

const NAVIGABLE = new Set(['launch', 'jurisdiction']);

const GROUP_ORDER: Array<{ key: keyof Omit<SearchResponse, 'query'>; label: string }> = [
  { key: 'launches', label: 'Launches' },
  { key: 'jurisdictions', label: 'Jurisdictions' },
  { key: 'documents', label: 'Documents' },
  { key: 'sessions', label: 'Sessions' },
  { key: 'obligations', label: 'Obligations' },
  { key: 'controls', label: 'Controls' },
];

function hitPath(type: string, id: string): string | null {
  if (type === 'launch') return `/launches/${id}`;
  if (type === 'jurisdiction') return `/jurisdictions/${id}`;
  return null;
}

export default function HeaderSearch() {
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<SearchResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const [open, setOpen] = useState(false);
  const [activeIndex, setActiveIndex] = useState(-1);

  const inputRef = useRef<HTMLInputElement>(null);
  const containerRef = useRef<HTMLDivElement>(null);
  const reqIdRef = useRef(0);
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const navigate = useNavigate();

  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key === 'k') {
        e.preventDefault();
        inputRef.current?.focus();
        inputRef.current?.select();
      }
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, []);

  useEffect(() => {
    const handler = (e: MouseEvent) => {
      if (containerRef.current && !containerRef.current.contains(e.target as Node)) {
        setOpen(false);
      }
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, []);

  useEffect(() => {
    if (timerRef.current) clearTimeout(timerRef.current);
    if (query.length < 2) {
      setResults(null);
      setLoading(false);
      setOpen(false);
      setActiveIndex(-1);
      return;
    }
    setLoading(true);
    setOpen(true);
    const id = ++reqIdRef.current;
    timerRef.current = setTimeout(() => {
      searchAll(query, 5)
        .then((res) => {
          if (reqIdRef.current === id) {
            setResults(res);
            setLoading(false);
            setActiveIndex(-1);
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

  const clickableHits = results
    ? GROUP_ORDER.flatMap(({ key }) =>
        (results[key] ?? []).filter((h) => NAVIGABLE.has(h.type)).map((h) => ({ ...h }))
      )
    : [];

  function handleKeyDown(e: React.KeyboardEvent<HTMLInputElement>) {
    if (e.key === 'Escape') {
      if (query) {
        setQuery('');
        setOpen(false);
      } else {
        inputRef.current?.blur();
      }
      return;
    }
    if (!open) return;
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      setActiveIndex((i) => Math.min(i + 1, clickableHits.length - 1));
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      setActiveIndex((i) => Math.max(i - 1, -1));
    } else if (e.key === 'Enter' && activeIndex >= 0) {
      const hit = clickableHits[activeIndex];
      const path = hitPath(hit.type, hit.id);
      if (path) {
        navigate(path);
        setQuery('');
        setOpen(false);
        inputRef.current?.blur();
      }
    }
  }

  const hasResults = results && GROUP_ORDER.some((g) => (results[g.key]?.length ?? 0) > 0);
  let clickableIdx = -1;

  return (
    <div ref={containerRef} style={{ position: 'relative', display: 'flex', alignItems: 'center' }}>
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: '6px',
          background: 'var(--bg-1, #111)',
          border: '1px solid var(--line-1, rgba(246,241,234,0.1))',
          borderRadius: '6px',
          padding: '0 8px',
          height: '28px',
          minWidth: '160px',
        }}
      >
        <svg width="12" height="12" viewBox="0 0 14 14" fill="none" style={{ flexShrink: 0, color: 'var(--ink-2, #888)' }}>
          <circle cx="6" cy="6" r="4.5" stroke="currentColor" strokeWidth="1.3" />
          <path d="M9.5 9.5L12 12" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round" />
        </svg>
        <input
          ref={inputRef}
          type="search"
          value={query}
          placeholder="Search…"
          onChange={(e) => setQuery(e.target.value)}
          onFocus={() => { if (query.length >= 2 && results) setOpen(true); }}
          onKeyDown={handleKeyDown}
          style={{
            background: 'transparent',
            border: 'none',
            outline: 'none',
            color: 'var(--ink-0, #f6f1ea)',
            fontSize: '13px',
            fontFamily: 'inherit',
            width: '100%',
          }}
        />
        {!query && (
          <kbd
            style={{
              fontSize: '10px',
              fontFamily: 'inherit',
              background: 'var(--bg-2, rgba(246,241,234,0.06))',
              border: '1px solid var(--line-1, rgba(246,241,234,0.1))',
              borderRadius: '3px',
              padding: '1px 4px',
              color: 'var(--ink-2, #888)',
              flexShrink: 0,
              lineHeight: '14px',
            }}
          >
            ⌘K
          </kbd>
        )}
      </div>

      {open && (
        <div
          style={{
            position: 'absolute',
            top: 'calc(100% + 6px)',
            left: 0,
            minWidth: '360px',
            maxWidth: '420px',
            background: 'var(--bg-1, #111)',
            border: '1px solid var(--line-1, rgba(246,241,234,0.1))',
            borderRadius: '8px',
            boxShadow: '0 16px 48px -8px rgba(0,0,0,0.6)',
            zIndex: 1000,
            overflow: 'hidden',
          }}
        >
          {loading && (
            <div style={{ padding: '10px 14px', fontSize: '12px', color: 'var(--ink-2, #888)', borderBottom: '1px solid var(--line-1, rgba(246,241,234,0.06))' }}>
              Searching…
            </div>
          )}

          {!loading && query.length >= 2 && !hasResults && (
            <div style={{ padding: '20px 14px', textAlign: 'center', fontSize: '13px', color: 'var(--ink-2, #888)' }}>
              No results
            </div>
          )}

          {hasResults && (
            <div style={{ maxHeight: '360px', overflowY: 'auto' }}>
              {GROUP_ORDER.map(({ key, label }) => {
                const hits = results![key] ?? [];
                if (!hits.length) return null;
                return (
                  <div key={key}>
                    <div
                      style={{
                        padding: '8px 14px 4px',
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
                      const clickable = NAVIGABLE.has(hit.type);
                      const path = hitPath(hit.type, hit.id);
                      if (clickable) clickableIdx++;
                      const myIdx = clickable ? clickableIdx : -1;
                      const isActive = myIdx === activeIndex;
                      return (
                        <div
                          key={`${hit.type}-${hit.id}`}
                          onClick={
                            clickable && path
                              ? () => {
                                  navigate(path);
                                  setQuery('');
                                  setOpen(false);
                                  inputRef.current?.blur();
                                }
                              : undefined
                          }
                          style={{
                            display: 'flex',
                            alignItems: 'center',
                            gap: '10px',
                            padding: '7px 14px',
                            cursor: clickable ? 'pointer' : 'default',
                            opacity: clickable ? 1 : 0.45,
                            background: isActive ? 'var(--bg-2, rgba(246,241,234,0.07))' : 'transparent',
                          }}
                          onMouseEnter={
                            clickable
                              ? (e) => { (e.currentTarget as HTMLDivElement).style.background = 'var(--bg-2, rgba(246,241,234,0.05))'; }
                              : undefined
                          }
                          onMouseLeave={
                            clickable
                              ? (e) => { (e.currentTarget as HTMLDivElement).style.background = isActive ? 'var(--bg-2, rgba(246,241,234,0.07))' : 'transparent'; }
                              : undefined
                          }
                        >
                          <div style={{ flex: 1, minWidth: 0 }}>
                            <div
                              style={{
                                fontSize: '13px',
                                fontWeight: 500,
                                color: 'var(--ink-0, #f6f1ea)',
                                whiteSpace: 'nowrap',
                                overflow: 'hidden',
                                textOverflow: 'ellipsis',
                              }}
                            >
                              {hit.title}
                            </div>
                            {hit.subtitle && (
                              <div
                                style={{
                                  fontSize: '11px',
                                  color: 'var(--ink-2, #888)',
                                  marginTop: '1px',
                                  whiteSpace: 'nowrap',
                                  overflow: 'hidden',
                                  textOverflow: 'ellipsis',
                                }}
                              >
                                {hit.subtitle}
                              </div>
                            )}
                          </div>
                        </div>
                      );
                    })}
                  </div>
                );
              })}
            </div>
          )}
        </div>
      )}
    </div>
  );
}
