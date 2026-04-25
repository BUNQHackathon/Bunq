import { useEffect, useState, useMemo, useCallback } from 'react';
import { listJurisdictionsOverview, getJurisdictionTriage, type JurisdictionOverview, type JurisdictionLaunchRow, type JurisdictionTriage } from '../api/jurisdictions';
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
  if (verdict === 'UNKNOWN') return '#3a3a3a';
  return '#FF7819';
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
  triage: JurisdictionTriage | null;
}

const GROUP_ACCENT: Record<'keep' | 'modify' | 'drop', string> = {
  keep:   'var(--success)',
  modify: 'var(--warning)',
  drop:   'var(--danger)',
};

const GROUP_LABEL: Record<'keep' | 'modify' | 'drop', string> = {
  keep:   'Compliant',
  modify: 'Needs changes',
  drop:   'Not compliant',
};

function TriageRow({ row, accent }: { row: JurisdictionLaunchRow; accent: string }) {
  const subtitle = row.summary ?? row.status ?? '';
  return (
    <div
      style={{ display: 'flex', alignItems: 'center', gap: 12, padding: '10px 14px', cursor: 'pointer', borderTop: '1px solid var(--line-1)' }}
      onClick={() => { window.location.href = `/launches/${row.launchId}`; }}
    >
      <span style={{ width: 8, height: 8, borderRadius: '50%', background: accent, flex: '0 0 auto' }} />
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ fontSize: 13, color: 'var(--ink-0)', fontWeight: 500 }}>{row.name}</div>
        <div style={{ fontSize: 12, color: 'var(--ink-2)', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{subtitle}</div>
      </div>
      <span style={{ color: 'var(--ink-3)', fontSize: 14, marginLeft: 'auto', flex: '0 0 auto' }}>›</span>
    </div>
  );
}

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

      <div className="juris__panel-body" style={{ overflowY: 'auto' }}>
        <div className="juris__summary">
          <button className="juris__stat">
            <div className="juris__stat-head">
              <span className="juris__stat-dot" style={{ background: 'var(--success)' }} />
              Compliant
            </div>
            <div className="juris__stat-num">
              {keepCount}<small>{pct(keepCount)}%</small>
            </div>
            <div className="juris__stat-bar">
              <div className="juris__stat-bar-fill" style={{ width: `${pct(keepCount)}%`, background: 'var(--success)' }} />
            </div>
          </button>

          <button className="juris__stat">
            <div className="juris__stat-head">
              <span className="juris__stat-dot" style={{ background: 'var(--warning)' }} />
              Needs changes
            </div>
            <div className="juris__stat-num">
              {modifyCount}<small>{pct(modifyCount)}%</small>
            </div>
            <div className="juris__stat-bar">
              <div className="juris__stat-bar-fill" style={{ width: `${pct(modifyCount)}%`, background: 'var(--warning)' }} />
            </div>
          </button>

          <button className="juris__stat">
            <div className="juris__stat-head">
              <span className="juris__stat-dot" style={{ background: 'var(--danger)' }} />
              Not compliant
            </div>
            <div className="juris__stat-num">
              {dropCount}<small>{pct(dropCount)}%</small>
            </div>
            <div className="juris__stat-bar">
              <div className="juris__stat-bar-fill" style={{ width: `${pct(dropCount)}%`, background: 'var(--danger)' }} />
            </div>
          </button>
        </div>

        {triage && (['keep', 'modify', 'drop'] as const).map((key) => {
          const rows   = triage[key];
          const accent = GROUP_ACCENT[key];
          const isOpen = open[key];
          return (
            <div key={key}>
              <div
                style={{ display: 'flex', alignItems: 'center', padding: '10px 14px', cursor: 'pointer', borderTop: '1px solid var(--line-1)' }}
                onClick={() => setOpen(prev => ({ ...prev, [key]: !prev[key] }))}
              >
                <span style={{ width: 8, height: 8, borderRadius: '50%', background: accent, flex: '0 0 auto', marginRight: 10 }} />
                <span style={{ fontSize: 12, color: 'var(--ink-1)', fontWeight: 500, flex: 1 }}>{GROUP_LABEL[key]}</span>
                <span style={{ fontSize: 12, color: 'var(--ink-3)', marginRight: 8 }}>{rows.length}</span>
                <span style={{ color: 'var(--ink-3)', fontSize: 12 }}>{isOpen ? '∨' : '›'}</span>
              </div>
              {isOpen && rows.map((row) => (
                <TriageRow key={row.launchId} row={row} accent={accent} />
              ))}
            </div>
          );
        })}
      </div>
    </div>
  );
}

// ── Page ──────────────────────────────────────────────────────────────────────

export default function JurisdictionsPage() {
  const [view, setView] = useState<'map' | 'globe'>('globe');
  const [overview, setOverview] = useState<JurisdictionOverview[] | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [selectedCode, setSelectedCode] = useState<string | null>(null); // ISO-2
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
    getJurisdictionTriage(selectedCode)
      .then(d => { if (!cancelled) setTriage(d); })
      .catch(() => { /* triage stays null; stat cards show 0s */ });
    return () => { cancelled = true; };
  }, [selectedCode]);

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
          triage={triage}
        />
      ) : (
        <JurisEmptyPanel />
      )}
    </div>
  );
}
