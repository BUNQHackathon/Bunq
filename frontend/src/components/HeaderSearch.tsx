import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { searchAll } from '../api/search';
import type { SearchResponse } from '../api/search';
import { IconSearch, IconFolders, IconExternal, IconDoc, IconChatBubble } from './icons';

const NAVIGABLE = new Set(['launch', 'jurisdiction', 'document']);

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
  if (type === 'document') return `/doc/${id}`;
  return null;
}

function HitIcon({ type }: { type: string }) {
  if (type === 'launch') return <IconFolders size={12} className="text-white/50 shrink-0" />;
  if (type === 'jurisdiction') return <IconExternal size={12} className="text-white/50 shrink-0" />;
  if (type === 'document') return <IconDoc size={12} className="text-white/50 shrink-0" />;
  if (type === 'session') return <IconChatBubble size={12} className="text-white/50 shrink-0" />;
  return <IconExternal size={12} className="text-white/50 shrink-0" />;
}

interface HeaderSearchProps {
  fullWidth?: boolean;
}

export default function HeaderSearch({ fullWidth }: HeaderSearchProps) {
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
    <div ref={containerRef} className="relative flex items-center">
      <div
        className={`flex items-center gap-1.5 bg-[#141414] border border-white/[0.08] rounded-full px-2.5 h-[30px] ${fullWidth ? 'w-full' : 'w-44 md:w-56'}`}
      >
        <IconSearch size={14} className="text-white/50 shrink-0" />
        <input
          ref={inputRef}
          type="search"
          value={query}
          placeholder="Search…"
          onChange={(e) => setQuery(e.target.value)}
          onFocus={() => { if (query.length >= 2 && results) setOpen(true); }}
          onKeyDown={handleKeyDown}
          className="bg-transparent text-[13px] text-white/85 placeholder-white/30 outline-none flex-1 min-w-0"
          style={{ caretColor: '#FF7819' }}
        />
        {!query && (
          <kbd className="font-mono text-[10px] text-white/30 select-none shrink-0">⌘K</kbd>
        )}
      </div>

      {open && (loading || hasResults || (query.length >= 2 && !loading && !hasResults)) && (
        <div
          className={`absolute top-full mt-2 rounded-xl overflow-hidden z-50 flex flex-col ${fullWidth ? 'left-0 right-0' : 'right-0 w-[min(380px,calc(100vw-24px))]'}`}
          style={{
            background: '#141414',
            border: '1px solid rgba(255,255,255,0.08)',
            boxShadow: '0 10px 40px rgba(0,0,0,0.5)',
          }}
        >
          {loading && (
            <div
              className="px-[14px] py-[10px] text-[12px] text-white/50"
              style={{ borderBottom: '1px solid rgba(255,255,255,0.06)' }}
            >
              Searching…
            </div>
          )}

          {!loading && query.length >= 2 && !hasResults && (
            <div className="px-[14px] py-5 text-center text-[13px] text-white/40">
              No results
            </div>
          )}

          {hasResults && (
            <div className="overflow-y-auto max-h-[360px]">
              {GROUP_ORDER.map(({ key, label }) => {
                const hits = results![key] ?? [];
                if (!hits.length) return null;
                return (
                  <div key={key}>
                    <div className="font-mono uppercase tracking-widest text-[10px] text-white/30 px-3.5 pt-3 pb-1.5 select-none">
                      {label}
                    </div>
                    {hits.map((hit) => {
                      const clickable = NAVIGABLE.has(hit.type);
                      const path = hitPath(hit.type, hit.id);
                      if (clickable) clickableIdx++;
                      const myIdx = clickable ? clickableIdx : -1;
                      const isActive = myIdx === activeIndex;

                      if (clickable) {
                        return (
                          <button
                            key={`${hit.type}-${hit.id}`}
                            type="button"
                            onClick={
                              path
                                ? () => {
                                    navigate(path);
                                    setQuery('');
                                    setOpen(false);
                                    inputRef.current?.blur();
                                  }
                                : undefined
                            }
                            className={`flex items-center gap-3 px-4 py-2 hover:bg-white/[0.04] cursor-pointer w-full text-left transition-colors${isActive ? ' bg-white/[0.06]' : ''}`}
                          >
                            <HitIcon type={hit.type} />
                            <span className="flex-1 text-[13px] text-white/85 truncate">{hit.title}</span>
                            {hit.subtitle && (
                              <span className="font-mono text-[10px] text-white/40 shrink-0">{hit.subtitle}</span>
                            )}
                          </button>
                        );
                      }

                      return (
                        <div
                          key={`${hit.type}-${hit.id}`}
                          className="flex items-center gap-3 px-4 py-2 opacity-50 cursor-default"
                        >
                          <HitIcon type={hit.type} />
                          <span className="flex-1 text-[13px] text-white/85 truncate">{hit.title}</span>
                          {hit.subtitle && (
                            <span className="font-mono text-[10px] text-white/40 shrink-0">{hit.subtitle}</span>
                          )}
                        </div>
                      );
                    })}
                  </div>
                );
              })}
            </div>
          )}

          <div
            className="font-mono text-[10px] text-white/30 px-[14px] py-[10px] shrink-0"
            style={{ borderTop: '1px solid rgba(255,255,255,0.05)' }}
          >
            ↑↓ to navigate · ↵ to select · esc to close
          </div>
        </div>
      )}
    </div>
  );
}
