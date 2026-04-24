import { useState, useEffect } from 'react';
import { Link, useParams, useNavigate } from 'react-router-dom';
import { getJurisdictionLaunches, type JurisdictionLaunchRow } from '../api/jurisdictions';
import { jurisdictionLabel, downloadProofPack } from '../api/launch';
import type { Verdict } from '../api/launch';
import KindBadge from '../components/KindBadge';
import { IconChevron, IconDownload, IconGraph } from '../components/icons';

// ── Helpers ───────────────────────────────────────────────────────────────────

function formatRelative(dateStr?: string): string {
  if (!dateStr) return '—';
  const diff = Date.now() - new Date(dateStr).getTime();
  const mins  = Math.floor(diff / 60_000);
  const hours = Math.floor(diff / 3_600_000);
  const days  = Math.floor(diff / 86_400_000);
  if (mins < 2)   return 'JUST NOW';
  if (hours < 1)  return `${mins}M AGO`;
  if (hours < 24) return `${hours}H AGO`;
  return `${days}D AGO`;
}

type Status = 'compliant' | 'warning' | 'noncompliant';

function verdictToStatus(v: Verdict): Status | null {
  switch (v) {
    case 'GREEN': return 'compliant';
    case 'AMBER': return 'warning';
    case 'RED':   return 'noncompliant';
    default:      return null;
  }
}

// ── JurisHeaderGradient ───────────────────────────────────────────────────────
// Verbatim port of the handoff component (three blurred blobs + SVG grain + masked title)

interface HeaderGradientProps {
  countryName: string;
  total: number;
  lastRunRelative: string;
}

function JurisHeaderGradient({ countryName, total, lastRunRelative }: HeaderGradientProps) {
  const headerHeight  = 260;
  const glowSpread    = 113;
  const color1        = '#eb2700';
  const color2        = '#C86334';
  const color3        = '#d9a67d';
  const stop1Height   = 98;
  const stop2Height   = 128;
  const stop3Height   = 148;
  const bgColor       = '#0b0a09';
  const blurAmount    = 25;
  const titleFadeStart = 15;
  const titleFadeEnd   = 115;

  return (
    <header
      className="juris__hero"
      style={{ minHeight: `${headerHeight}px`, background: bgColor }}
    >
      {/* Grain */}
      <svg
        aria-hidden="true"
        style={{
          position: 'absolute', inset: 0, width: '100%', height: '100%',
          pointerEvents: 'none', mixBlendMode: 'soft-light', zIndex: 1,
        }}
      >
        <filter id="juris-grain">
          <feTurbulence type="turbulence" baseFrequency="0.65" numOctaves={3} stitchTiles="stitch" />
          <feColorMatrix type="saturate" values="0" />
          <feComponentTransfer>
            <feFuncR type="linear" slope={4} intercept={-1.5} />
            <feFuncG type="linear" slope={4} intercept={-1.5} />
            <feFuncB type="linear" slope={4} intercept={-1.5} />
          </feComponentTransfer>
        </filter>
        <rect width="100%" height="100%" filter="url(#juris-grain)" opacity="0.25" />
      </svg>

      {/* Gradient blobs rising from below */}
      <div style={{ position: 'absolute', inset: 0, overflow: 'hidden', zIndex: 0, pointerEvents: 'none' }}>
        <div style={{
          position: 'absolute',
          width: `${glowSpread}%`, aspectRatio: '1994 / 717',
          left: '50%', bottom: `${-stop1Height}%`,
          transform: 'translateX(-50%)',
          borderRadius: '50%',
          background: `radial-gradient(ellipse farthest-side at center, ${color1} 29%, #292928 100%)`,
          filter: `blur(${blurAmount}px)`,
          opacity: 1,
        }} />
        <div style={{
          position: 'absolute',
          width: `${glowSpread}%`, aspectRatio: '1994 / 717',
          left: '52%', bottom: `${-stop2Height}%`,
          transform: 'translateX(-50%)',
          borderRadius: '50%',
          background: color2,
          filter: `blur(${blurAmount}px)`,
          opacity: 0.95,
        }} />
        <div style={{
          position: 'absolute',
          width: `${glowSpread + 20}%`, aspectRatio: '2222 / 717',
          left: '54%', bottom: `${-stop3Height}%`,
          transform: 'translateX(-50%)',
          borderRadius: '50%',
          background: color3,
          filter: `blur(${blurAmount + 4}px)`,
          opacity: 0.9,
        }} />
      </div>

      <div style={{ position: 'relative', zIndex: 2, textAlign: 'left', height: '120px', padding: '10px 32px 1px' }}>
        <div className="juris__hero-eyebrow">
          <span className="mono-label" style={{ color: '#D8AC78' }}>JURISDICTION</span>
          <span className="mono-label" style={{ color: 'rgba(255,255,255,0.45)' }}>
            ·&nbsp; {total} LAUNCHES &nbsp;·&nbsp; EVALUATED {lastRunRelative}
          </span>
        </div>
        <h1
          className="juris__hero-title"
          style={{
            WebkitMaskImage: `linear-gradient(to bottom, black ${titleFadeStart}%, transparent ${titleFadeEnd}%)`,
            maskImage: `linear-gradient(to bottom, black ${titleFadeStart}%, transparent ${titleFadeEnd}%)`,
          }}
        >
          {countryName}
        </h1>
      </div>
    </header>
  );
}

// ── Group order ───────────────────────────────────────────────────────────────

const GROUP_ORDER: Array<{ status: Status; label: string; verdict: Verdict }> = [
  { status: 'compliant',    label: 'Compliant',      verdict: 'GREEN' },
  { status: 'warning',      label: 'Needs changes',  verdict: 'AMBER' },
  { status: 'noncompliant', label: 'Not compliant',  verdict: 'RED'   },
];

// ── Page ──────────────────────────────────────────────────────────────────────

export default function JurisdictionDetailPage() {
  const { code } = useParams<{ code: string }>();
  const navigate  = useNavigate();

  const [data,       setData]       = useState<{ code: string; launches: JurisdictionLaunchRow[] } | null>(null);
  const [error,      setError]      = useState<string | null>(null);
  const [activeFilter, setActiveFilter] = useState<Status | 'all'>('all');
  const [openGroups, setOpenGroups] = useState<Record<Status, boolean>>({
    compliant: true, warning: true, noncompliant: true,
  });

  useEffect(() => {
    if (!code) return;
    let cancelled = false;
    getJurisdictionLaunches(code)
      .then(d  => !cancelled && setData(d))
      .catch(e => !cancelled && setError(e instanceof Error ? e.message : String(e)));
    return () => { cancelled = true; };
  }, [code]);

  const label   = jurisdictionLabel(code ?? '');
  const launches = data?.launches ?? [];

  // Freshest lastRunAt across all launches
  const lastRunRelative = (() => {
    const dates = launches.map(l => l.lastRunAt).filter(Boolean) as string[];
    if (!dates.length) return '—';
    const newest = dates.reduce((a, b) => new Date(a) > new Date(b) ? a : b);
    return formatRelative(newest);
  })();

  // Summary counts
  const counts: Record<Status, number> = { compliant: 0, warning: 0, noncompliant: 0 };
  launches.forEach(l => {
    const s = verdictToStatus(l.verdict);
    if (s) counts[s]++;
  });
  const total = launches.length;
  const pct   = (n: number) => total > 0 ? Math.round((n / total) * 100) : 0;

  function toggleGroup(s: Status) {
    setOpenGroups(prev => ({ ...prev, [s]: !prev[s] }));
  }

  // Filtered groups
  const visibleGroups = GROUP_ORDER.filter(
    g => activeFilter === 'all' || activeFilter === g.status,
  );

  return (
    <div className="juris" style={{ gridTemplateColumns: '1fr', height: '100vh', minHeight: 0 }}>
      <div className="juris__panel" style={{ borderLeft: 'none', overflowY: 'auto' }}>

        {/* Back link — above hero */}
        <div style={{
          position: 'absolute', top: 14, left: 32, zIndex: 20,
        }}>
          <Link
            to="/jurisdictions"
            className="mono-label"
            style={{ color: 'var(--ink-2)', textDecoration: 'none', display: 'inline-flex', alignItems: 'center', gap: 4 }}
          >
            ← All jurisdictions
          </Link>
        </div>

        {/* Loading / error */}
        {data === null && !error && (
          <div style={{ padding: '48px 32px', textAlign: 'center' }}>
            <span className="mono-label" style={{ color: 'var(--ink-3)' }}>Loading…</span>
          </div>
        )}
        {error && (
          <div style={{
            margin: '24px 32px',
            padding: '12px 16px',
            background: 'rgba(217,74,74,0.1)',
            border: '1px solid rgba(217,74,74,0.3)',
            borderRadius: 'var(--r-md)',
            color: 'var(--danger)',
            fontSize: 13,
          }}>
            {error}
          </div>
        )}

        {/* Hero — always rendered once we have label */}
        {(data !== null || error) && (
          <JurisHeaderGradient
            countryName={label}
            total={total}
            lastRunRelative={lastRunRelative}
          />
        )}

        {data !== null && (
          <div className="juris__panel-body">

            {total === 0 ? (
              /* Empty state */
              <div className="juris__empty">
                <div className="juris__empty-h">No launches in {label} yet</div>
                <div className="juris__empty-sub">
                  There are no product launches tracked in this jurisdiction.
                </div>
                <button className="btn btn--orange btn--sm" style={{ marginTop: 8 }}>
                  Run feasibility scan
                </button>
              </div>
            ) : (
              <>
                {/* Stats summary */}
                <div className="juris__summary">
                  {GROUP_ORDER.map(({ status, label: gLabel }) => (
                    <button
                      key={status}
                      className={`juris__stat ${activeFilter === status ? 'juris__stat--active' : ''}`}
                      onClick={() => setActiveFilter(prev => prev === status ? 'all' : status)}
                    >
                      <div className="juris__stat-head">
                        <span className="juris__stat-dot" style={{
                          background: status === 'compliant'
                            ? 'var(--success)'
                            : status === 'warning'
                              ? 'var(--warning)'
                              : 'var(--danger)',
                        }} />
                        {gLabel}
                      </div>
                      <div className="juris__stat-num">
                        {counts[status]}<small>{pct(counts[status])}%</small>
                      </div>
                      <div className="juris__stat-bar">
                        <div
                          className="juris__stat-bar-fill"
                          style={{
                            width: `${pct(counts[status])}%`,
                            background: status === 'compliant'
                              ? 'var(--success)'
                              : status === 'warning'
                                ? 'var(--warning)'
                                : 'var(--danger)',
                          }}
                        />
                      </div>
                    </button>
                  ))}
                </div>

                {/* Grouped collapsible list */}
                <div className="juris__list thin-scroll">
                  {visibleGroups.map(({ status, label: gLabel, verdict }) => {
                    const items = launches.filter(l => l.verdict === verdict);
                    if (items.length === 0) return null;
                    const isOpen = openGroups[status];

                    return (
                      <div className="juris__group" key={status}>
                        <button
                          className={`juris__group-row juris__group-row--${status}`}
                          onClick={() => toggleGroup(status)}
                        >
                          <span
                            className={`juris__group-caret ${isOpen ? 'juris__group-caret--open' : ''}`}
                          >
                            <IconChevron size={12} />
                          </span>
                          <span className={`juris__feat-status juris__feat-status--${status}`} />
                          <span className="juris__group-name">{gLabel}</span>
                          <span className="juris__group-count">{items.length}</span>
                        </button>

                        {isOpen && (
                          <div className="juris__group-features">
                            {items.map((launch) => (
                              <div
                                key={launch.launchId}
                                className="juris__feature"
                                onClick={() => navigate(`/launches/${launch.launchId}`)}
                                style={{ cursor: 'pointer' }}
                              >
                                <span className={`juris__feat-status juris__feat-status--${status}`} />

                                <div className="juris__feat-body">
                                  <div className="juris__feat-name">
                                    <KindBadge kind={launch.kind} />{' '}
                                    {launch.name}
                                  </div>
                                  <div className={[
                                    'juris__feat-note',
                                    status === 'warning'      ? 'juris__feat-note--warn' : '',
                                    status === 'noncompliant' ? 'juris__feat-note--bad'  : '',
                                  ].filter(Boolean).join(' ')}>
                                    {launch.gapsCount} gaps
                                    {' · '}
                                    {launch.sanctionsHits} sanction{launch.sanctionsHits !== 1 ? 's' : ''}
                                    {' · '}
                                    last run {formatRelative(launch.lastRunAt)}
                                  </div>
                                </div>

                                {/* Action buttons — stop propagation so row click doesn't also fire */}
                                <span className="juris__feat-arrow" style={{ display: 'flex', gap: 6, alignItems: 'center' }}>
                                  <button
                                    title="Download proof pack"
                                    disabled={!launch.proofPackAvailable}
                                    onClick={(e) => {
                                      e.stopPropagation();
                                      if (code) downloadProofPack(launch.launchId, code);
                                    }}
                                    className="btn btn--icon btn--sm"
                                    style={{
                                      opacity: launch.proofPackAvailable ? 1 : 0.3,
                                      cursor: launch.proofPackAvailable ? 'pointer' : 'not-allowed',
                                      color: 'var(--ink-1)',
                                    }}
                                  >
                                    <IconDownload size={12} />
                                  </button>
                                  <button
                                    title="Open compliance graph"
                                    onClick={(e) => {
                                      e.stopPropagation();
                                      if (code) navigate(`/jurisdictions/${code}/launches/${launch.launchId}`);
                                    }}
                                    className="btn btn--icon btn--sm"
                                    style={{ color: 'var(--ink-1)' }}
                                  >
                                    <IconGraph size={12} />
                                  </button>
                                </span>
                              </div>
                            ))}
                          </div>
                        )}
                      </div>
                    );
                  })}
                </div>
              </>
            )}
          </div>
        )}
      </div>
    </div>
  );
}
