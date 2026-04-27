import { useEffect, useState, useMemo, useCallback } from 'react';
import { useParams } from 'react-router-dom';
import { useAuth } from '../auth/useAuth';
import { listJurisdictionsOverview, getJurisdictionTriage, type JurisdictionOverview, type JurisdictionTriage } from '../api/jurisdictions';
import { jurisdictionLabel, JURISDICTION_CATALOG } from '../api/launch';
import type { Verdict } from '../api/launch';
import WorldMapD3 from '../components/WorldMapD3';
import WorldMapGlobe from '../components/WorldMapGlobe';
import HeroGradient from '../components/HeroGradient';
import { IconSearch, IconChevron } from '../components/icons';
import { ISO2_TO_ISO3, ISO3_TO_ISO2, MOCK_COUNTRY_COLOR, MOCK_COUNTRY_LABEL, BUNQ_GRADIENT_COLOR } from '../api/mockCountries';

const VALID_CODES = new Set(JURISDICTION_CATALOG.map(j => j.code));

// ── Color helpers ─────────────────────────────────────────────────────────────

const VERDICT_COLOR: Record<Verdict, string> = {
  GREEN:   '#e8c97a',
  AMBER:   '#e89a4f',
  RED:     '#d94a2e',
  PENDING: '#1E1E1E',
  UNKNOWN: '#3a3a3a',
};

function overviewToColor(verdict: Verdict): string {
  return VERDICT_COLOR[verdict] ?? '#1E1E1E';
}

// ── Sub-components ────────────────────────────────────────────────────────────

interface MapPanelProps {
  overview: JurisdictionOverview[] | null;
  mapData: Map<string, { color: string; label?: string }>;
  selectedIso3: string | undefined;
  onSelect: (iso3: string) => void;
  view: 'map' | 'globe';
  onViewChange: (v: 'map' | 'globe') => void;
  searchQuery: string;
  onSearch: (q: string) => void;
}

function JurisMapPanel({
  overview, mapData, selectedIso3, onSelect, view, onViewChange, searchQuery, onSearch,
}: MapPanelProps) {
  const active   = (overview ?? []).filter(o => o.aggregateVerdict !== 'PENDING').length;
  const inactive = (overview ?? []).filter(o => o.aggregateVerdict === 'PENDING').length;
  const total    = (overview ?? []).length;

  const [searchFocused, setSearchFocused] = useState(false);
  const suggestions = useMemo(() => {
    const q = searchQuery.toLowerCase().trim();
    if (!q) return [];
    return (overview ?? []).filter((o) => {
      const lbl = jurisdictionLabel(o.code).toLowerCase();
      return lbl.includes(q) || o.code.toLowerCase().includes(q);
    }).slice(0, 8);
  }, [overview, searchQuery]);

  function pickSuggestion(code: string) {
    const iso3 = ISO2_TO_ISO3[code] ?? code;
    onSelect(iso3);
    onSearch('');
    setSearchFocused(false);
  }

  return (
    <div className="juris__map">
      {/* Head */}
      <div className="juris__map-head">
        <div className="juris__map-title">
          <span className="juris__map-eyebrow">
            Compliance map · {total} markets
          </span>
          <span className="juris__map-h">Where bunq operates</span>
        </div>

        <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-end', gap: 8 }}>
          {/* 2D / 3D toggle — minimal chip row */}
          <div style={{ display: 'flex', gap: 2, background: 'rgba(255,255,255,0.05)', borderRadius: 999, padding: 3 }}>
            {(['globe', 'map'] as const).map((v) => (
              <button
                key={v}
                onClick={() => onViewChange(v)}
                className="chip chip--sm"
                style={{
                  background: view === v ? 'var(--orange-wash)' : 'transparent',
                  color: view === v ? 'var(--orange)' : 'var(--ink-2)',
                  border: view === v ? '1px solid rgba(239,106,42,0.3)' : '1px solid transparent',
                  borderRadius: 999,
                  cursor: 'pointer',
                  padding: '4px 14px',
                  fontWeight: view === v ? 600 : 400,
                }}
              >
                {v === 'map' ? '2D' : '3D'}
              </button>
            ))}
          </div>

          {/* Legend */}
          <div className="juris__legend">
            <span className="juris__legend-item">
              <span className="juris__legend-dot" style={{ background: '#e8c97a' }} />
              Compliant
            </span>
            <span className="juris__legend-item">
              <span className="juris__legend-dot" style={{ background: 'repeating-linear-gradient(45deg, #e8c97a 0 2px, #d94a2e 2px 4px)' }} />
              Needs changes
            </span>
            <span className="juris__legend-item">
              <span className="juris__legend-dot" style={{ background: '#d94a2e' }} />
              Not compliant
            </span>
            <span className="juris__legend-item">
              <span className="juris__legend-dot" style={{ background: 'rgba(160,150,140,0.5)' }} />
              Inactive
            </span>
          </div>
        </div>
      </div>

      {/* Canvas */}
      <div className="juris__map-canvas">
        {overview === null ? (
          <span className="mono-label" style={{ color: 'var(--ink-3)' }}>
            Loading jurisdictions…
          </span>
        ) : view === 'globe' ? (
          <WorldMapGlobe
            data={mapData}
            selected={selectedIso3}
            onSelect={onSelect}
          />
        ) : (
          <WorldMapD3
            data={mapData}
            selected={selectedIso3}
            onSelect={onSelect}
          />
        )}
      </div>

      {/* Foot */}
      <div className="juris__map-foot">
        {searchFocused && suggestions.length > 0 && (
          <div className="juris__autocomplete">
            {suggestions.map((s) => (
              <div
                key={s.code}
                className="juris__autocomplete-item"
                onMouseDown={(e) => {
                  e.preventDefault();
                  pickSuggestion(s.code);
                }}
              >
                <span className="juris__autocomplete-label">{jurisdictionLabel(s.code)}</span>
                <span className="juris__autocomplete-code">{s.code}</span>
              </div>
            ))}
          </div>
        )}
        <span style={{ color: 'var(--ink-3)', flexShrink: 0, display: 'inline-flex' }}>
          <IconSearch size={13} />
        </span>
        <input
          value={searchQuery}
          onChange={(e) => onSearch(e.target.value)}
          onFocus={() => setSearchFocused(true)}
          onBlur={() => setSearchFocused(false)}
          placeholder="Jump to country, region, or regulation…"
          autoComplete="off"
        />
        <div className="juris__map-foot-stats">
          <span>{active} active</span>
          <span>·</span>
          <span>{inactive} inactive</span>
        </div>
      </div>
    </div>
  );
}

// ── Right panel: empty state ──────────────────────────────────────────────────

function JurisEmptyPanel() {
  return (
    <div className="juris__panel">
      <div className="juris__empty">
        <div className="juris__empty-h">Select a country</div>
        <div className="juris__empty-sub">
          Pick a market on the map to see the compliance breakdown across product launches.
        </div>
      </div>
    </div>
  );
}

// ── Right panel: selected country ────────────────────────────────────────────

interface OverviewPanelProps {
  overview: JurisdictionOverview;
  triage: JurisdictionTriage | null;
}


const STATUS_BY_KEY = { keep: 'compliant', modify: 'warning', drop: 'noncompliant' } as const;
const LABEL_BY_KEY  = { keep: 'Compliant', modify: 'Needs changes', drop: 'Not compliant' } as const;

function JurisOverviewPanel({ overview, triage }: OverviewPanelProps) {
  const code  = overview.code;
  const label = jurisdictionLabel(code);

  const keepCount   = triage?.keep.length   ?? 0;
  const modifyCount = triage?.modify.length ?? 0;
  const dropCount   = triage?.drop.length   ?? 0;
  const triageTotal = keepCount + modifyCount + dropCount;
  const total       = triage ? triageTotal : overview.launchCount;

  const pct = (n: number) => triageTotal > 0 ? Math.round((n / triageTotal) * 100) : 0;

  const [open, setOpen] = useState<{ keep: boolean; modify: boolean; drop: boolean }>({
    keep: false, modify: true, drop: true,
  });

  return (
    <div className="juris__panel">
      <header
        className="juris__hero"
        style={{ minHeight: 220, position: 'relative', overflow: 'hidden' }}
      >
        <HeroGradient animate />

        <div
          style={{
            position: 'relative',
            zIndex: 2,
            textAlign: 'left',
            padding: '10px 32px 24px',
          }}
        >
          <div className="juris__hero-eyebrow">
            <span className="mono-label" style={{ color: '#D8AC78' }}>JURISDICTION</span>
            <span className="mono-label" style={{ color: 'rgba(255,255,255,0.45)' }}>
              · {total} LAUNCH{total !== 1 ? 'ES' : ''}
            </span>
          </div>
          <h1
            className="juris__hero-title"
            style={{
              WebkitMaskImage: 'linear-gradient(to bottom, black 15%, transparent 115%)',
              maskImage: 'linear-gradient(to bottom, black 15%, transparent 115%)',
            }}
          >
            {label}
          </h1>
        </div>
      </header>

      <div className="juris__panel-body" style={{ overflowY: 'auto' }}>
        <div className="juris__summary">
          <div className="juris__stat">
            <div className="juris__stat-head">
              <span className="juris__stat-dot" style={{ background: '#e8c97a' }} />
              Compliant
            </div>
            <div className="juris__stat-num">
              {keepCount}<small>{pct(keepCount)}%</small>
            </div>
            <div className="juris__stat-bar">
              <div className="juris__stat-bar-fill" style={{ width: `${pct(keepCount)}%`, background: '#e8c97a' }} />
            </div>
          </div>

          <div className="juris__stat">
            <div className="juris__stat-head">
              <span className="juris__stat-dot" style={{ background: 'repeating-linear-gradient(45deg, #e8c97a 0 2px, #d94a2e 2px 4px)' }} />
              Needs changes
            </div>
            <div className="juris__stat-num">
              {modifyCount}<small>{pct(modifyCount)}%</small>
            </div>
            <div className="juris__stat-bar">
              <div className="juris__stat-bar-fill" style={{ width: `${pct(modifyCount)}%`, background: 'repeating-linear-gradient(45deg, #e8c97a 0 2px, #d94a2e 2px 4px)' }} />
            </div>
          </div>

          <div className="juris__stat">
            <div className="juris__stat-head">
              <span className="juris__stat-dot" style={{ background: '#d94a2e' }} />
              Not compliant
            </div>
            <div className="juris__stat-num">
              {dropCount}<small>{pct(dropCount)}%</small>
            </div>
            <div className="juris__stat-bar">
              <div className="juris__stat-bar-fill" style={{ width: `${pct(dropCount)}%`, background: '#d94a2e' }} />
            </div>
          </div>
        </div>

        <div className="juris__list thin-scroll">
          {(['keep', 'modify', 'drop'] as const)
            .filter((key) => triage && triage[key].length > 0)
            .map((key) => {
              const rows   = triage![key];
              const status = STATUS_BY_KEY[key];
              const isOpen = open[key];
              return (
                <div className="juris__group" key={key}>
                  <button
                    className={`juris__group-row juris__group-row--${status}`}
                    onClick={() => setOpen((p) => ({ ...p, [key]: !p[key] }))}
                  >
                    <span className={`juris__group-caret ${isOpen ? 'juris__group-caret--open' : ''}`}>
                      <span style={{ display: 'inline-flex', transform: 'rotate(-90deg)' }}>
                        <IconChevron size={12} />
                      </span>
                    </span>
                    <span className={`juris__feat-status juris__feat-status--${status}`} />
                    <span className="juris__group-name">{LABEL_BY_KEY[key]}</span>
                    <span className="juris__group-count">{rows.length}</span>
                  </button>

                  {isOpen && (
                    <div className="juris__group-features">
                      {rows.map((row) => (
                        <div
                          key={row.launchId}
                          className="juris__feature"
                          onClick={() => { window.location.href = `/launches/${row.launchId}`; }}
                        >
                          <span className={`juris__feat-status juris__feat-status--${status}`} />
                          <div className="juris__feat-body">
                            <div className="juris__feat-name">{row.name}</div>
                          </div>
                          <span className="juris__feat-arrow">
                            <span style={{ display: 'inline-flex', transform: 'rotate(-90deg)' }}>
                              <IconChevron size={12} />
                            </span>
                          </span>
                        </div>
                      ))}
                    </div>
                  )}
                </div>
              );
            })}
        </div>
      </div>
    </div>
  );
}

// ── Page ──────────────────────────────────────────────────────────────────────

export default function JurisdictionsPage() {
  const { isAuthenticated } = useAuth();
  const { code: routeCode } = useParams<{ code?: string }>();
  const [view, setView] = useState<'map' | 'globe'>('globe');
  const [overview, setOverview] = useState<JurisdictionOverview[] | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const normalizedRouteCode = routeCode && VALID_CODES.has(routeCode.toUpperCase()) ? routeCode.toUpperCase() : null;
  const [selectedCode, setSelectedCode] = useState<string | null>(normalizedRouteCode); // ISO-2
  const [searchQuery, setSearchQuery] = useState('');
  const [triage, setTriage] = useState<JurisdictionTriage | null>(null);

  // ── Load overview ──────────────────────────────────────────────────────────
  useEffect(() => {
    listJurisdictionsOverview()
      .then(setOverview)
      .catch((err: unknown) => {
        setLoadError(err instanceof Error ? err.message : 'Failed to load jurisdictions');
        setOverview([]);
      });
  }, []);

  // ── Load triage when selection changes ────────────────────────────────────
  useEffect(() => {
    setTriage(null);
    if (!selectedCode) return;
    let cancelled = false;
    getJurisdictionTriage(selectedCode, !isAuthenticated)
      .then(d => { if (!cancelled) setTriage(d); })
      .catch(() => { /* triage stays null; stat cards show 0s */ });
    return () => { cancelled = true; };
  }, [selectedCode]);

  useEffect(() => {
    setSelectedCode(routeCode && VALID_CODES.has(routeCode.toUpperCase()) ? routeCode.toUpperCase() : null);
  }, [routeCode]);

  // ── Build map data (ISO-3 → color) using semantic tokens ─────────────────
  const mapData = useMemo(() => {
    const m = new Map<string, { color: string; label?: string }>();
    // 1. Real backend data
    (overview ?? []).forEach((o) => {
      const iso3 = ISO2_TO_ISO3[o.code] ?? o.code;
      m.set(iso3, {
        color: overviewToColor(o.aggregateVerdict),
        label: jurisdictionLabel(o.code),
      });
    });
    // 2. Demo overlay — colors the rest of the world
    for (const [iso3, color] of Object.entries(MOCK_COUNTRY_COLOR)) {
      const existing = m.get(iso3);
      m.set(iso3, {
        color,
        label: existing?.label ?? MOCK_COUNTRY_LABEL[iso3] ?? iso3,
      });
    }
    // 3. BUNQ gradient — markets where BUNQ operates always win, in brand colors
    for (const [iso3, color] of Object.entries(BUNQ_GRADIENT_COLOR)) {
      const existing = m.get(iso3);
      m.set(iso3, {
        color,
        label: existing?.label ?? MOCK_COUNTRY_LABEL[iso3] ?? iso3,
      });
    }
    return m;
  }, [overview]);

  const selectedIso3 = selectedCode ? (ISO2_TO_ISO3[selectedCode] ?? selectedCode) : undefined;

  // ── Handle map click ───────────────────────────────────────────────────────
  const handleSelect = useCallback((iso3: string) => {
    if (!iso3) { setSelectedCode(null); return; }
    const iso2 = ISO3_TO_ISO2[iso3] ?? iso3;
    setSelectedCode(iso2);
  }, []);

  // ── Derived ────────────────────────────────────────────────────────────────
  const selectedOverview = selectedCode
    ? (overview ?? []).find((o) => o.code === selectedCode) ?? null
    : null;

  // Filter overview by search (used for foot stats when no selection)
  const _filteredOverview = useMemo(() => {
    const q = searchQuery.toLowerCase().trim();
    if (!q) return overview ?? [];
    return (overview ?? []).filter((o) => {
      const lbl = jurisdictionLabel(o.code).toLowerCase();
      return lbl.includes(q) || o.code.toLowerCase().includes(q);
    });
  }, [overview, searchQuery]);
  void _filteredOverview; // used indirectly via searchQuery passed to JurisMapPanel

  // Enter in the search input: resolve query → select the country (opens the
  // right panel, same as clicking it on the map).
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Enter') return;
      const t = e.target as HTMLElement | null;
      if (!t || t.tagName !== 'INPUT') return;
      if (!(t as HTMLInputElement).placeholder?.startsWith('Jump to country')) return;
      const q = searchQuery.toLowerCase().trim();
      if (!q) return;
      const items = overview ?? [];
      const exact = items.find((o) => {
        const lbl = jurisdictionLabel(o.code).toLowerCase();
        return lbl === q || o.code.toLowerCase() === q;
      });
      const match = exact ?? items.find((o) => {
        const lbl = jurisdictionLabel(o.code).toLowerCase();
        return lbl.includes(q) || o.code.toLowerCase().includes(q);
      });
      if (match) {
        setSelectedCode(match.code);
        setSearchQuery('');
        (t as HTMLInputElement).blur();
      }
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [overview, searchQuery]);

  // ── Render ─────────────────────────────────────────────────────────────────
  return (
    <div
      className="juris"
      style={{ height: '100%', minHeight: 0 }}
    >
      {/* Error banner — lives outside the grid, overlaid at top */}
      {loadError && (
        <div
          style={{
            position: 'fixed', top: 12, left: '50%', transform: 'translateX(-50%)',
            zIndex: 100,
            background: 'rgba(217,74,74,0.12)',
            border: '1px solid rgba(217,74,74,0.3)',
            color: 'var(--danger)',
            borderRadius: 'var(--r-md)',
            padding: '10px 18px',
            fontSize: 13,
          }}
        >
          {loadError}
        </div>
      )}

      <JurisMapPanel
        overview={overview}
        mapData={mapData}
        selectedIso3={selectedIso3}
        onSelect={handleSelect}
        view={view}
        onViewChange={setView}
        searchQuery={searchQuery}
        onSearch={setSearchQuery}
      />

      {selectedOverview ? (
        <JurisOverviewPanel
          overview={selectedOverview}
          triage={triage}
        />
      ) : (
        <JurisEmptyPanel />
      )}
    </div>
  );
}
