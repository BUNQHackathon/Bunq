import { useEffect, useState, useMemo, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { listJurisdictionsOverview, type JurisdictionOverview } from '../api/jurisdictions';
import { jurisdictionLabel } from '../api/launch';
import type { Verdict } from '../api/launch';
import WorldMapD3 from '../components/WorldMapD3';
import WorldMapGlobe from '../components/WorldMapGlobe';
import { IconSearch } from '../components/icons';

// ── ISO conversions ───────────────────────────────────────────────────────────

const ISO2_TO_ISO3: Record<string, string> = {
  NL: 'NLD', DE: 'DEU', FR: 'FRA', GB: 'GBR', UK: 'GBR',
  US: 'USA', IE: 'IRL', AT: 'AUT', ES: 'ESP', IT: 'ITA', BE: 'BEL',
};

const ISO3_TO_ISO2: Record<string, string> = Object.fromEntries(
  Object.entries(ISO2_TO_ISO3).map(([a, b]) => [b, a]),
);

// ── Color helpers ─────────────────────────────────────────────────────────────

function overviewToColor(verdict: Verdict): string {
  if (verdict === 'PENDING') return '#1E1E1E';
  return '#FF7819';
}

function verdictStatus(verdict: Verdict): 'compliant' | 'warning' | 'noncompliant' | 'pending' {
  switch (verdict) {
    case 'GREEN': return 'compliant';
    case 'AMBER': return 'warning';
    case 'RED':   return 'noncompliant';
    default:      return 'pending';
  }
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
                  background: view === v ? 'var(--orange)' : 'transparent',
                  color: view === v ? '#fff' : 'var(--ink-2)',
                  border: 'none',
                  borderRadius: 999,
                  cursor: 'pointer',
                  padding: '4px 14px',
                }}
              >
                {v === 'map' ? '2D' : '3D'}
              </button>
            ))}
          </div>

          {/* Legend */}
          <div className="juris__legend">
            <span className="juris__legend-item">
              <span className="juris__legend-dot" style={{ background: '#FF7819' }} />
              Active
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
  onClear: () => void;
  onNavigate: () => void;
}

function JurisOverviewPanel({ overview, onClear, onNavigate }: OverviewPanelProps) {
  const code  = overview.code;
  const label = jurisdictionLabel(code);

  const green  = overview.aggregateVerdict === 'GREEN'   ? 1 : 0;
  const amber  = overview.aggregateVerdict === 'AMBER'   ? 1 : 0;
  const red    = overview.aggregateVerdict === 'RED'     ? 1 : 0;
  const total  = overview.launchCount;

  const pct = (n: number) => total > 0 ? Math.round((n / total) * 100) : 0;

  return (
    <div className="juris__panel">
      {/* Simplified hero — no gradient blobs, just eyebrow + name */}
      <header
        className="juris__hero"
        style={{ minHeight: 100, background: 'var(--bg-1)', justifyContent: 'flex-end', padding: '18px 28px 18px' }}
      >
        <div className="juris__hero-eyebrow">
          <span className="mono-label" style={{ color: 'var(--orange)' }}>JURISDICTION</span>
          <span className="mono-label" style={{ color: 'var(--ink-3)' }}>
            · {total} LAUNCH{total !== 1 ? 'ES' : ''}
          </span>
        </div>
        <h1
          className="juris__hero-title"
          style={{ fontSize: 36, marginBottom: 0, color: 'var(--ink-0)' }}
        >
          {label}
        </h1>
      </header>

      <div className="juris__panel-body">
        {/* Summary stats */}
        <div className="juris__summary">
          <button className={`juris__stat ${overview.aggregateVerdict === 'GREEN' ? 'juris__stat--active' : ''}`}>
            <div className="juris__stat-head">
              <span className="juris__stat-dot" style={{ background: 'var(--success)' }} />
              Compliant
            </div>
            <div className="juris__stat-num">
              {green}<small>{pct(green)}%</small>
            </div>
            <div className="juris__stat-bar">
              <div className="juris__stat-bar-fill" style={{ width: `${pct(green)}%`, background: 'var(--success)' }} />
            </div>
          </button>

          <button className={`juris__stat ${overview.aggregateVerdict === 'AMBER' ? 'juris__stat--active' : ''}`}>
            <div className="juris__stat-head">
              <span className="juris__stat-dot" style={{ background: 'var(--warning)' }} />
              Needs changes
            </div>
            <div className="juris__stat-num">
              {amber}<small>{pct(amber)}%</small>
            </div>
            <div className="juris__stat-bar">
              <div className="juris__stat-bar-fill" style={{ width: `${pct(amber)}%`, background: 'var(--warning)' }} />
            </div>
          </button>

          <button className={`juris__stat ${overview.aggregateVerdict === 'RED' ? 'juris__stat--active' : ''}`}>
            <div className="juris__stat-head">
              <span className="juris__stat-dot" style={{ background: 'var(--danger)' }} />
              Not compliant
            </div>
            <div className="juris__stat-num">
              {red}<small>{pct(red)}%</small>
            </div>
            <div className="juris__stat-bar">
              <div className="juris__stat-bar-fill" style={{ width: `${pct(red)}%`, background: 'var(--danger)' }} />
            </div>
          </button>
        </div>

        {/* Worst verdict note */}
        {overview.worstVerdict && overview.worstVerdict !== 'PENDING' && (
          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <span
              className={`juris__feat-status juris__feat-status--${verdictStatus(overview.worstVerdict)}`}
            />
            <span className="mono-label" style={{ color: 'var(--ink-2)' }}>
              WORST: {overview.worstVerdict}
            </span>
          </div>
        )}
      </div>

      {/* Back + open detail — always pinned at panel bottom */}
      <div className="juris__panel-foot">
        <button
          className="btn btn--ghost btn--sm"
          onClick={onClear}
          style={{ color: 'var(--ink-2)', border: '1px solid var(--line-1)' }}
        >
          ← All countries
        </button>
        <button
          className="btn btn--orange"
          style={{ flex: 1 }}
          onClick={onNavigate}
        >
          Open {label} detail →
        </button>
      </div>
    </div>
  );
}

// ── Page ──────────────────────────────────────────────────────────────────────

export default function JurisdictionsPage() {
  const navigate = useNavigate();

  const [view, setView] = useState<'map' | 'globe'>('globe');
  const [overview, setOverview] = useState<JurisdictionOverview[] | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [selectedCode, setSelectedCode] = useState<string | null>(null); // ISO-2
  const [searchQuery, setSearchQuery] = useState('');

  // ── Load overview ──────────────────────────────────────────────────────────
  useEffect(() => {
    listJurisdictionsOverview()
      .then(setOverview)
      .catch((err: unknown) => {
        setLoadError(err instanceof Error ? err.message : 'Failed to load jurisdictions');
        setOverview([]);
      });
  }, []);

  // ── Build map data (ISO-3 → color) using semantic tokens ─────────────────
  const mapData = useMemo(() => {
    const m = new Map<string, { color: string; label?: string }>();
    (overview ?? []).forEach((o) => {
      const iso3 = ISO2_TO_ISO3[o.code] ?? o.code;
      m.set(iso3, {
        color: overviewToColor(o.aggregateVerdict),
        label: jurisdictionLabel(o.code),
      });
    });
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
          onClear={() => setSelectedCode(null)}
          onNavigate={() => navigate(`/jurisdictions/${selectedCode}`)}
        />
      ) : (
        <JurisEmptyPanel />
      )}
    </div>
  );
}
