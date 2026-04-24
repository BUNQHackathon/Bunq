import { useEffect, useState, useMemo, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { listJurisdictionsOverview, type JurisdictionOverview } from '../api/jurisdictions';
import { jurisdictionFlag, jurisdictionLabel } from '../api/launch';
import WorldMapD3 from '../components/WorldMapD3';
import WorldMapGlobe from '../components/WorldMapGlobe';
import VerdictPill, { verdictToHex } from '../components/VerdictPill';

// ── ISO conversions ───────────────────────────────────────────────────────────

const ISO2_TO_ISO3: Record<string, string> = {
  NL: 'NLD', DE: 'DEU', FR: 'FRA', GB: 'GBR', UK: 'GBR',
  US: 'USA', IE: 'IRL', AT: 'AUT', ES: 'ESP', IT: 'ITA', BE: 'BEL',
};

const ISO3_TO_ISO2: Record<string, string> = Object.fromEntries(
  Object.entries(ISO2_TO_ISO3).map(([a, b]) => [b, a]),
);

// ── Component ─────────────────────────────────────────────────────────────────

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

  // ── Build map data (ISO-3 → color) ────────────────────────────────────────
  const mapData = useMemo(() => {
    const m = new Map<string, { color: string; label?: string }>();
    (overview ?? []).forEach((o) => {
      const iso3 = ISO2_TO_ISO3[o.code] ?? o.code;
      m.set(iso3, {
        color: verdictToHex(o.aggregateVerdict),
        label: jurisdictionLabel(o.code),
      });
    });
    return m;
  }, [overview]);

  // ISO-3 for map components
  const selectedIso3 = selectedCode ? (ISO2_TO_ISO3[selectedCode] ?? selectedCode) : undefined;

  // ── Handle map click: set selected + navigate ─────────────────────────────
  const handleSelect = useCallback((iso3: string) => {
    if (!iso3) {
      setSelectedCode(null);
      return;
    }
    const iso2 = ISO3_TO_ISO2[iso3] ?? iso3;
    setSelectedCode(iso2);
    navigate(`/jurisdictions/${iso2}`);
  }, [navigate]);

  // ── Derived sidebar data ───────────────────────────────────────────────────
  const selectedOverview = selectedCode
    ? (overview ?? []).find((o) => o.code === selectedCode) ?? null
    : null;

  // ── Search filtering ───────────────────────────────────────────────────────
  const filteredOverview = useMemo(() => {
    const q = searchQuery.toLowerCase().trim();
    if (!q) return overview ?? [];
    return (overview ?? []).filter((o) => {
      const label = jurisdictionLabel(o.code).toLowerCase();
      return label.includes(q) || o.code.toLowerCase().includes(q);
    });
  }, [overview, searchQuery]);

  // ── Render ─────────────────────────────────────────────────────────────────
  return (
    <div className="flex flex-col" style={{ minHeight: '100vh', color: '#E8E8E8' }}>

      {/* Header */}
      <div className="px-6 py-10 max-w-6xl mx-auto w-full">
        <div className="flex items-center justify-between mb-6">
          <div>
            <h1 className="text-2xl font-semibold text-white mb-1">Jurisdictions</h1>
            <p className="font-mono text-[11px] uppercase tracking-wider" style={{ color: '#6B6B6B' }}>
              Global compliance coverage map
            </p>
          </div>
          <button
            onClick={() => navigate('/launches')}
            className="px-4 py-2 rounded-xl text-[13px] font-medium transition-all cursor-pointer border"
            style={{
              background: 'rgba(255,120,25,0.14)',
              borderColor: 'rgba(255,120,25,0.35)',
              color: '#FF9F55',
            }}
          >
            ← Launches
          </button>
        </div>
      </div>

      {/* Error banner */}
      {loadError && (
        <div
          className="mx-6 mb-4 max-w-6xl mx-auto w-full rounded-xl px-4 py-3 text-[13px]"
          style={{ background: 'rgba(239,68,68,0.12)', border: '1px solid rgba(239,68,68,0.3)', color: '#ef4444' }}
        >
          {loadError}
        </div>
      )}

      {/* Main panel: visualization + sidebar */}
      <div
        className="flex-1 flex overflow-hidden mx-6 mb-6 max-w-6xl mx-auto w-full rounded-xl"
        style={{
          background: '#0D0D0D',
          border: '1px solid rgba(255,255,255,0.06)',
          minHeight: 640,
          maxWidth: '72rem',
        }}
      >

        {/* Left: toolbar + visualization */}
        <div className="flex flex-col flex-1 min-w-0 overflow-hidden">

          {/* Toolbar strip */}
          <div
            className="flex items-center gap-4 px-5 shrink-0"
            style={{ height: 44, borderBottom: '1px solid rgba(255,255,255,0.06)' }}
          >
            {/* 2D / 3D toggle */}
            <div className="flex gap-[2px] p-[3px] rounded-full" style={{ background: 'rgba(255,255,255,0.05)' }}>
              {(['globe', 'map'] as const).map((v) => (
                <button
                  key={v}
                  onClick={() => setView(v)}
                  className="rounded-full px-[22px] py-[7px] text-[13.5px] font-semibold transition-all duration-[220ms] cursor-pointer border-0"
                  style={{
                    background: view === v ? '#FF7819' : 'transparent',
                    color: view === v ? '#fff' : 'rgba(255,255,255,0.4)',
                  }}
                >
                  {v === 'map' ? 'Map' : 'Globe'}
                </button>
              ))}
            </div>

            {/* Search */}
            <div className="relative ml-auto">
              <span
                className="absolute left-[13px] top-1/2 -translate-y-1/2 pointer-events-none"
                style={{ color: 'rgba(255,255,255,0.3)' }}
              >
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                  <circle cx="11" cy="11" r="8" />
                  <line x1="21" y1="21" x2="16.65" y2="16.65" />
                </svg>
              </span>
              <input
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                placeholder="Search country…"
                autoComplete="off"
                className="rounded-full text-[13px] text-white outline-none w-56 pl-[38px] pr-4 py-2"
                style={{
                  background: 'rgba(255,255,255,0.05)',
                  border: '1px solid rgba(255,255,255,0.08)',
                }}
                onFocus={(e) => { (e.target as HTMLInputElement).style.borderColor = 'rgba(255,120,25,0.4)'; }}
                onBlur={(e) => { (e.target as HTMLInputElement).style.borderColor = 'rgba(255,255,255,0.08)'; }}
              />
            </div>
          </div>

          {/* Visualization area */}
          {overview === null ? (
            <div className="flex-1 flex items-center justify-center">
              <span className="font-mono text-[13px]" style={{ color: 'rgba(255,255,255,0.3)' }}>
                Loading jurisdictions…
              </span>
            </div>
          ) : (
            <div className="flex-1 relative overflow-hidden">
              {/* 2D Map */}
              <div
                className="absolute inset-0 transition-opacity duration-300"
                style={{ opacity: view === 'map' ? 1 : 0, pointerEvents: view === 'map' ? 'auto' : 'none' }}
              >
                <WorldMapD3
                  data={mapData}
                  selected={selectedIso3}
                  onSelect={handleSelect}
                  height={640}
                />
              </div>

              {/* 3D Globe */}
              <div
                className="absolute inset-0 transition-opacity duration-300"
                style={{ opacity: view === 'globe' ? 1 : 0, pointerEvents: view === 'globe' ? 'auto' : 'none' }}
              >
                <WorldMapGlobe
                  data={mapData}
                  selected={selectedIso3}
                  onSelect={handleSelect}
                  height={640}
                />
              </div>
            </div>
          )}
        </div>

        {/* Right sidebar */}
        <div
          className="flex flex-col overflow-hidden shrink-0"
          style={{ width: 300, minWidth: 300, borderLeft: '1px solid rgba(255,255,255,0.05)' }}
        >
          <div
            className="px-[22px] pt-[22px] pb-[18px] shrink-0"
            style={{ borderBottom: '1px solid rgba(255,255,255,0.05)' }}
          >
            <div
              className="text-[11px] font-bold uppercase tracking-[0.1em]"
              style={{ color: 'rgba(255,255,255,0.3)' }}
            >
              {selectedCode ? 'Country' : 'Jurisdiction Overview'}
            </div>
          </div>

          <div
            className="flex-1 overflow-y-auto px-[22px] py-[20px]"
            style={{ scrollbarWidth: 'thin', scrollbarColor: '#222 transparent' }}
          >
            {!selectedCode ? (
              <>
                {/* Country list */}
                {filteredOverview.length > 0 ? (
                  <div className="flex flex-col gap-2 mb-5">
                    {filteredOverview.map((o) => (
                      <button
                        key={o.code}
                        onClick={() => {
                          setSelectedCode(o.code);
                          navigate(`/jurisdictions/${o.code}`);
                        }}
                        className="flex items-center justify-between rounded-xl px-[15px] py-[12px] cursor-pointer transition-all duration-150 text-left border-0 w-full"
                        style={{ background: '#141414', border: '1px solid rgba(255,255,255,0.06)' }}
                        onMouseEnter={(e) => {
                          (e.currentTarget as HTMLButtonElement).style.background = '#1C1C1C';
                          (e.currentTarget as HTMLButtonElement).style.borderColor = 'rgba(255,120,25,0.2)';
                        }}
                        onMouseLeave={(e) => {
                          (e.currentTarget as HTMLButtonElement).style.background = '#141414';
                          (e.currentTarget as HTMLButtonElement).style.borderColor = 'rgba(255,255,255,0.06)';
                        }}
                      >
                        <span className="text-[13px] font-medium" style={{ color: 'rgba(255,255,255,0.85)' }}>
                          {jurisdictionFlag(o.code)} {jurisdictionLabel(o.code)}
                        </span>
                        <VerdictPill verdict={o.aggregateVerdict} showEmoji={false} />
                      </button>
                    ))}
                  </div>
                ) : overview !== null && overview.length === 0 ? (
                  <div
                    className="rounded-xl px-[14px] py-3 text-[12px] leading-[1.5]"
                    style={{
                      background: 'rgba(255,120,25,0.06)',
                      border: '1px solid rgba(255,120,25,0.14)',
                      color: 'rgba(255,255,255,0.5)',
                    }}
                  >
                    No jurisdictions available.
                  </div>
                ) : (
                  <div
                    className="rounded-xl px-[14px] py-3 text-[12px] leading-[1.5]"
                    style={{
                      background: 'rgba(255,120,25,0.06)',
                      border: '1px solid rgba(255,120,25,0.14)',
                      color: 'rgba(255,255,255,0.5)',
                    }}
                  >
                    Click a country on the map or globe to see details.
                  </div>
                )}

                {/* Legend */}
                <div className="mt-4">
                  <div className="text-[10px] font-bold uppercase tracking-[0.08em] mb-[10px]" style={{ color: 'rgba(255,255,255,0.3)' }}>
                    Verdict
                  </div>
                  {[
                    { color: '#22c55e', label: 'Green — fully covered' },
                    { color: '#f59e0b', label: 'Amber — partial coverage' },
                    { color: '#ef4444', label: 'Red — open gaps' },
                    { color: '#6B6B6B', label: 'Pending — running analysis' },
                  ].map(({ color, label }) => (
                    <div key={label} className="flex items-center gap-[10px] mb-2">
                      <div className="w-[10px] h-[10px] rounded-full shrink-0" style={{ background: color }} />
                      <div className="text-[13px]" style={{ color: 'rgba(255,255,255,0.6)' }}>{label}</div>
                    </div>
                  ))}
                </div>
              </>
            ) : (
              <>
                {/* Back button */}
                <button
                  onClick={() => setSelectedCode(null)}
                  className="flex items-center gap-[5px] text-[12px] mb-[18px] cursor-pointer bg-transparent border-0 p-0 transition-colors duration-150"
                  style={{ color: 'rgba(255,255,255,0.3)' }}
                  onMouseEnter={(e) => (e.currentTarget.style.color = 'rgba(255,255,255,0.6)')}
                  onMouseLeave={(e) => (e.currentTarget.style.color = 'rgba(255,255,255,0.3)')}
                >
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                    <polyline points="15 18 9 12 15 6" />
                  </svg>
                  All countries
                </button>

                {/* Country header */}
                <div className="mb-[18px]">
                  <div className="text-[26px] font-bold tracking-[-0.025em] text-white leading-[1.15] mb-2">
                    {jurisdictionFlag(selectedCode)} {jurisdictionLabel(selectedCode)}
                  </div>
                  {selectedOverview && (
                    <VerdictPill verdict={selectedOverview.aggregateVerdict} />
                  )}
                </div>

                {/* Stats */}
                {selectedOverview && (
                  <div
                    className="rounded-xl px-[18px] py-4 mb-[14px]"
                    style={{ background: '#141414', border: '1px solid rgba(255,255,255,0.06)' }}
                  >
                    <div className="text-[10px] font-bold uppercase tracking-[0.08em] mb-2" style={{ color: 'rgba(255,255,255,0.3)' }}>
                      Summary
                    </div>
                    <div className="text-[13px]" style={{ color: 'rgba(255,255,255,0.7)' }}>
                      <span className="font-semibold text-white">{selectedOverview.launchCount}</span> launches
                    </div>
                    <div className="text-[12px] mt-1" style={{ color: 'rgba(255,255,255,0.45)' }}>
                      worst: <span style={{ color: verdictToHex(selectedOverview.worstVerdict) }}>{selectedOverview.worstVerdict}</span>
                    </div>
                  </div>
                )}

                {/* Open detail button */}
                <button
                  onClick={() => navigate(`/jurisdictions/${selectedCode}`)}
                  className="w-full rounded-xl px-4 py-3 text-[13px] font-semibold cursor-pointer transition-all duration-150 border-0"
                  style={{
                    background: 'rgba(255,120,25,0.14)',
                    border: '1px solid rgba(255,120,25,0.35)',
                    color: '#FF9F55',
                  }}
                  onMouseEnter={(e) => { (e.currentTarget as HTMLButtonElement).style.background = 'rgba(255,120,25,0.22)'; }}
                  onMouseLeave={(e) => { (e.currentTarget as HTMLButtonElement).style.background = 'rgba(255,120,25,0.14)'; }}
                >
                  Open detail →
                </button>
              </>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
