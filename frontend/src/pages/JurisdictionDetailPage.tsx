import { useState, useEffect } from 'react';
import { Link, useParams, useNavigate } from 'react-router-dom';
import { getJurisdictionTriage, type JurisdictionLaunchRow, type JurisdictionTriage } from '../api/jurisdictions';
import { jurisdictionLabel, downloadProofPack } from '../api/launch';
import KindBadge from '../components/KindBadge';
import { IconDownload, IconGraph } from '../components/icons';

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

// ── JurisHeaderGradient ───────────────────────────────────────────────────────

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

// ── Kanban column configs ──────────────────────────────────────────────────────

const COLUMNS: Array<{
  key: keyof Pick<JurisdictionTriage, 'keep' | 'modify' | 'drop'>;
  label: string;
  accent: string;
  dotColor: string;
}> = [
  { key: 'keep',   label: 'KEEP',   accent: 'var(--success)', dotColor: 'var(--success)' },
  { key: 'modify', label: 'MODIFY', accent: 'var(--warning)', dotColor: 'var(--warning)' },
  { key: 'drop',   label: 'DROP',   accent: 'var(--danger)',  dotColor: 'var(--danger)'  },
];

// ── Kanban card ───────────────────────────────────────────────────────────────

interface KanbanCardProps {
  row: JurisdictionLaunchRow;
  code: string;
  accent: string;
}

function KanbanCard({ row, code, accent }: KanbanCardProps) {
  const navigate = useNavigate();

  return (
    <div
      className="juris__feature"
      style={{
        cursor: 'pointer',
        display: 'flex',
        flexDirection: 'column',
        gap: 8,
        padding: '12px 14px',
        borderLeft: `3px solid ${accent}`,
      }}
      onClick={() => navigate(`/launches/${row.launchId}`)}
    >
      {/* Top row: kind badge + name */}
      <div className="juris__feat-name" style={{ display: 'flex', alignItems: 'center', gap: 6, flexWrap: 'wrap' }}>
        <KindBadge kind={row.kind} />
        <Link
          to={`/launches/${row.launchId}`}
          onClick={(e) => e.stopPropagation()}
          style={{ color: 'var(--ink-0)', textDecoration: 'none', fontWeight: 500 }}
        >
          {row.name}
        </Link>
      </div>

      {/* Summary */}
      {row.summary && (
        <div style={{ fontSize: 12, color: 'var(--ink-2)', lineHeight: 1.4 }}>
          {row.summary}
        </div>
      )}

      {/* Required changes */}
      {row.requiredChanges && row.requiredChanges.length > 0 && (
        <ul style={{ margin: 0, paddingLeft: 16, fontSize: 12, color: 'var(--warning)', lineHeight: 1.5 }}>
          {row.requiredChanges.map((item, i) => (
            <li key={i}>{item}</li>
          ))}
        </ul>
      )}

      {/* Blockers */}
      {row.blockers && row.blockers.length > 0 && (
        <ul style={{ margin: 0, paddingLeft: 16, fontSize: 12, color: 'var(--danger)', lineHeight: 1.5 }}>
          {row.blockers.map((item, i) => (
            <li key={i}>{item}</li>
          ))}
        </ul>
      )}

      {/* Footer: stats */}
      <div className="juris__feat-note" style={{ fontSize: 11 }}>
        {row.gapsCount} gap{row.gapsCount !== 1 ? 's' : ''}
        {' · '}
        {row.sanctionsHits} sanction{row.sanctionsHits !== 1 ? 's' : ''}
        {' · '}
        last run {formatRelative(row.lastRunAt)}
      </div>

      {/* Actions */}
      <div
        style={{ display: 'flex', gap: 6, marginTop: 2 }}
        onClick={(e) => e.stopPropagation()}
      >
        <button
          title="Download proof pack"
          disabled={!row.proofPackAvailable}
          onClick={(e) => {
            e.stopPropagation();
            downloadProofPack(row.launchId, code);
          }}
          className="btn btn--icon btn--sm"
          style={{
            opacity: row.proofPackAvailable ? 1 : 0.3,
            cursor: row.proofPackAvailable ? 'pointer' : 'not-allowed',
            color: 'var(--ink-1)',
            display: 'inline-flex',
            alignItems: 'center',
            gap: 4,
            fontSize: 11,
          }}
        >
          <IconDownload size={11} />
          <span>Proof Pack</span>
        </button>
        <button
          title="Open compliance graph"
          onClick={(e) => {
            e.stopPropagation();
            navigate(`/jurisdictions/${code}/launches/${row.launchId}`);
          }}
          className="btn btn--icon btn--sm"
          style={{
            color: 'var(--ink-1)',
            display: 'inline-flex',
            alignItems: 'center',
            gap: 4,
            fontSize: 11,
          }}
        >
          <IconGraph size={11} />
          <span>Graph</span>
        </button>
      </div>
    </div>
  );
}

// ── Page ──────────────────────────────────────────────────────────────────────

export default function JurisdictionDetailPage() {
  const { code } = useParams<{ code: string }>();

  const [triage, setTriage] = useState<JurisdictionTriage | null>(null);
  const [error,  setError]  = useState<string | null>(null);

  useEffect(() => {
    if (!code) return;
    let cancelled = false;
    getJurisdictionTriage(code)
      .then(d  => !cancelled && setTriage(d))
      .catch(e => !cancelled && setError(e instanceof Error ? e.message : String(e)));
    return () => { cancelled = true; };
  }, [code]);

  const label = jurisdictionLabel(code ?? '');

  const allRows = triage
    ? [...triage.keep, ...triage.modify, ...triage.drop]
    : [];

  const total = allRows.length;

  const lastRunRelative = (() => {
    const dates = allRows.map(r => r.lastRunAt).filter(Boolean) as string[];
    if (!dates.length) return '—';
    const newest = dates.reduce((a, b) => new Date(a) > new Date(b) ? a : b);
    return formatRelative(newest);
  })();

  return (
    <div className="juris" style={{ gridTemplateColumns: '1fr', height: '100vh', minHeight: 0 }}>
      <div className="juris__panel" style={{ borderLeft: 'none', overflowY: 'auto' }}>

        {/* Back link — above hero */}
        <div style={{ position: 'absolute', top: 14, left: 32, zIndex: 20 }}>
          <Link
            to="/jurisdictions"
            className="mono-label"
            style={{ color: 'var(--ink-2)', textDecoration: 'none', display: 'inline-flex', alignItems: 'center', gap: 4 }}
          >
            ← All jurisdictions
          </Link>
        </div>

        {/* Loading / error */}
        {triage === null && !error && (
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

        {/* Hero — always rendered once we have data */}
        {(triage !== null || error) && (
          <JurisHeaderGradient
            countryName={label}
            total={total}
            lastRunRelative={lastRunRelative}
          />
        )}

        {triage !== null && (
          <div className="juris__panel-body">
            {total === 0 ? (
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
              /* 3-column kanban */
              <div
                style={{
                  display: 'grid',
                  gridTemplateColumns: 'repeat(3, 1fr)',
                  gap: 16,
                  padding: '16px 0',
                }}
              >
                {COLUMNS.map(({ key, label: colLabel, accent, dotColor }) => {
                  const rows = triage[key];
                  return (
                    <div key={key} style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
                      {/* Column header */}
                      <div
                        className="juris__stat"
                        style={{ cursor: 'default', pointerEvents: 'none' }}
                      >
                        <div className="juris__stat-head">
                          <span className="juris__stat-dot" style={{ background: dotColor }} />
                          {colLabel}
                        </div>
                        <div className="juris__stat-num">
                          {rows.length}
                        </div>
                        <div className="juris__stat-bar">
                          <div
                            className="juris__stat-bar-fill"
                            style={{
                              width: total > 0 ? `${Math.round((rows.length / total) * 100)}%` : '0%',
                              background: accent,
                            }}
                          />
                        </div>
                      </div>

                      {/* Cards */}
                      {rows.length === 0 ? (
                        <div className="juris__empty" style={{ padding: '16px 12px', textAlign: 'center' }}>
                          <span className="mono-label" style={{ fontSize: 11, color: 'var(--ink-3)' }}>
                            Nothing here
                          </span>
                        </div>
                      ) : (
                        rows.map((row) => (
                          <KanbanCard
                            key={row.launchId}
                            row={row}
                            code={code ?? ''}
                            accent={accent}
                          />
                        ))
                      )}
                    </div>
                  );
                })}
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  );
}
