import { useEffect, useState, useMemo, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { listJurisdictionsOverview, type JurisdictionOverview } from '../api/jurisdictions';
import { jurisdictionFlag, jurisdictionLabel } from '../api/launch';
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
  switch (verdict) {
    case 'GREEN':   return 'var(--success)';
    case 'AMBER':   return 'var(--warning)';
    case 'RED':     return 'var(--danger)';
    default:        return 'rgba(160,150,140,0.4)';
  }
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
              <span className="juris__legend-dot" style={{ background: 'var(--success)' }} />
              Compliant
            </span>
            <span className="juris__legend-item">
              <span className="juris__legend-dot" style={{ background: 'var(--warning)' }} />
              Needs review
            </span>
            <span className="juris__legend-item">
              <span className="juris__legend-dot" style={{ background: 'var(--danger)' }} />
              Breaches
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
        ) : (
          <>
            <div
              style={{
                position: 'absolute', inset: 0,
                opacity: view === 'map' ? 1 : 0,
                pointerEvents: view === 'map' ? 'auto' : 'none',
                transition: 'opacity 0.3s',
              }}
            >
              <WorldMapD3
                data={mapData}
                selected={selectedIso3}
                onSelect={onSelect}
                height={640}
              />
            </div>
            <div
              style={{
                position: 'absolute', inset: 0,
                opacity: view === 'globe' ? 1 : 0,
                pointerEvents: view === 'globe' ? 'auto' : 'none',
                transition: 'opacity 0.3s',
              }}
            >
              <WorldMapGlobe
                data={mapData}
                selected={selectedIso3}
                onSelect={onSelect}
                height={640}
              />
            </div>
          </>
        )}
      </div>

      {/* Foot */}
      <div className="juris__map-foot">
        <span style={{ color: 'var(--ink-3)', flexShrink: 0, display: 'inline-flex' }}>
          <IconSearch size={13} />
        </span>
        <input
          value={searchQuery}
          onChange={(e) => onSearch(e.target.value)}
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
  const flag  = jurisdictionFlag(code);

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
          {flag} {label}
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

        {/* Back + open detail */}
        <div style={{ display: 'flex', gap: 8, marginTop: 'auto', paddingBottom: 24 }}>
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

  // ── Render ─────────────────────────────────────────────────────────────────
  return (
    <div
      className="juris"
      style={{ height: '100vh', minHeight: 0 }}
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
