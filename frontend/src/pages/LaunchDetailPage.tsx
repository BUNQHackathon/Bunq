import { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { useNavigate, useParams, Link } from 'react-router-dom';
import {
  getLaunch,
  downloadProofPack,
  jurisdictionFlag,
  jurisdictionLabel,
  type LaunchDetail,
  type JurisdictionRun,
  type Verdict,
  type JurisdictionStatus,
} from '../api/launch';
import WorldMapD3 from '../components/WorldMapD3';
import WorldMapGlobe from '../components/WorldMapGlobe';
import VerdictPill, { verdictToHex } from '../components/VerdictPill';

// ── ISO mappings ──────────────────────────────────────────────────────────────
const ISO2_TO_ISO3: Record<string, string> = {
  NL: 'NLD', DE: 'DEU', FR: 'FRA', GB: 'GBR', UK: 'GBR',
  US: 'USA', IE: 'IRL', AT: 'AUT', ES: 'ESP', IT: 'ITA', BE: 'BEL',
};
const ISO3_TO_ISO2: Record<string, string> = Object.fromEntries(
  Object.entries(ISO2_TO_ISO3).map(([a, b]) => [b, a]),
);

// ── Aggregate verdict (worst of children) ────────────────────────────────────
const VERDICT_RANK: Record<Verdict, number> = { GREEN: 0, AMBER: 1, RED: 2, PENDING: -1 };

function worstVerdict(rs: { verdict: Verdict }[]): Verdict | null {
  const valid = rs.filter((r) => VERDICT_RANK[r.verdict] >= 0);
  if (valid.length === 0) return rs.length > 0 ? 'PENDING' : null;
  return valid.reduce(
    (w, r) => (VERDICT_RANK[r.verdict] > VERDICT_RANK[w.verdict] ? r : w),
    valid[0],
  ).verdict;
}

type AggregateState =
  | { kind: 'running' }
  | { kind: 'failed' }
  | { kind: 'verdict'; verdict: Verdict }
  | null;

function aggregateState(rs: { status: JurisdictionStatus; verdict: Verdict }[]): AggregateState {
  if (rs.length === 0) return null;
  if (rs.some((r) => r.status === 'RUNNING' || r.status === 'PENDING')) return { kind: 'running' };
  const completed = rs.filter((r) => r.status === 'COMPLETE');
  if (completed.length === 0) return { kind: 'failed' };
  const worst = worstVerdict(completed);
  return worst ? { kind: 'verdict', verdict: worst } : null;
}

// ── Pulse keyframe injected once ─────────────────────────────────────────────
const PULSE_STYLE_ID = 'ld-pulse-style';
if (typeof document !== 'undefined' && !document.getElementById(PULSE_STYLE_ID)) {
  const s = document.createElement('style');
  s.id = PULSE_STYLE_ID;
  s.textContent = `@keyframes ldPulse { 0%,100%{opacity:1} 50%{opacity:.4} }`;
  document.head.appendChild(s);
}

// ── Status helpers ────────────────────────────────────────────────────────────
type StatusKey = 'compliant' | 'warning' | 'noncompliant';

function verdictToStatus(v: Verdict): StatusKey {
  if (v === 'GREEN') return 'compliant';
  if (v === 'RED') return 'noncompliant';
  return 'warning';
}

function runStatusKey(run: JurisdictionRun): StatusKey {
  if (run.status === 'RUNNING' || run.status === 'PENDING') return 'warning';
  if (run.status === 'FAILED') return 'noncompliant';
  return verdictToStatus(run.verdict);
}

function statusLabel(key: StatusKey, isRunning: boolean): string {
  if (isRunning) return 'Running…';
  if (key === 'compliant') return 'Compliant';
  if (key === 'warning') return 'Needs review';
  return 'Breach';
}

// ── Hero component (inline) ───────────────────────────────────────────────────
interface HeroProps {
  title: string;
  total: number;
  ok: number;
  review: number;
  block: number;
  anyRunning: boolean;
}

function Hero({ title, total, ok, review, block, anyRunning }: HeroProps) {
  const headerHeight = 220;
  const glowSpread = 113;
  const color1 = '#eb2700';
  const color2 = '#C86334';
  const color3 = '#d9a67d';
  // % of each ellipse's own height pushed below the hero — controls how much peeks up.
  // Smaller = more visible. Layered so the red core peeks most, amber least.
  const stop1Sink = 65; // red core: ~35% visible
  const stop2Sink = 85; // orange:    ~15% visible
  const stop3Sink = 92; // amber:      ~8% visible
  const bgColor = '#0b0a09';
  const blurAmount = 25;
  const titleFadeStart = 15;
  const titleFadeEnd = 115;

  return (
    <header
      className="juris__hero"
      style={{ minHeight: `${headerHeight}px`, background: bgColor }}
    >
      <svg
        aria-hidden="true"
        style={{
          position: 'absolute',
          inset: 0,
          width: '100%',
          height: '100%',
          pointerEvents: 'none',
          mixBlendMode: 'soft-light',
          zIndex: 1,
        }}
      >
        <defs>
          <filter id="ldp-grain">
            <feTurbulence type="turbulence" baseFrequency="0.65" numOctaves={3} stitchTiles="stitch" />
            <feColorMatrix type="saturate" values="0" />
            <feComponentTransfer>
              <feFuncR type="linear" slope={4} intercept={-1.5} />
              <feFuncG type="linear" slope={4} intercept={-1.5} />
              <feFuncB type="linear" slope={4} intercept={-1.5} />
            </feComponentTransfer>
          </filter>
        </defs>
        <rect width="100%" height="100%" filter="url(#ldp-grain)" opacity="0.25" />
      </svg>

      <div
        style={{
          position: 'absolute',
          inset: 0,
          overflow: 'hidden',
          zIndex: 0,
          pointerEvents: 'none',
        }}
      >
        <div
          style={{
            position: 'absolute',
            width: `${glowSpread}%`,
            aspectRatio: '1994 / 717',
            left: '50%',
            bottom: 0,
            transform: `translate(-50%, ${stop1Sink}%)`,
            borderRadius: '50%',
            background: `radial-gradient(ellipse farthest-side at center, ${color1} 29%, #292928 100%)`,
            filter: `blur(${blurAmount}px)`,
          }}
        />
        <div
          style={{
            position: 'absolute',
            width: `${glowSpread}%`,
            aspectRatio: '1994 / 717',
            left: '52%',
            bottom: 0,
            transform: `translate(-50%, ${stop2Sink}%)`,
            borderRadius: '50%',
            background: color2,
            filter: `blur(${blurAmount}px)`,
            opacity: 0.95,
          }}
        />
        <div
          style={{
            position: 'absolute',
            width: `${glowSpread + 20}%`,
            aspectRatio: '2222 / 717',
            left: '54%',
            bottom: 0,
            transform: `translate(-50%, ${stop3Sink}%)`,
            borderRadius: '50%',
            background: color3,
            filter: `blur(${blurAmount + 4}px)`,
            opacity: 0.9,
          }}
        />
      </div>

      <div
        style={{
          position: 'relative',
          zIndex: 2,
          textAlign: 'left',
          padding: '10px 32px 1px',
        }}
      >
        <div className="juris__hero-eyebrow">
          <span className="mono-label" style={{ color: '#D8AC78' }}>
            LAUNCH BREAKDOWN
          </span>
          <span className="mono-label" style={{ color: 'rgba(255,255,255,0.45)' }}>
            {anyRunning
              ? `· ${total} JURISDICTIONS · RUNNING`
              : `· ${total} JURISDICTIONS · ${ok} OK · ${review} REVIEW · ${block} BLOCK`}
          </span>
        </div>
        <h1
          className="juris__hero-title"
          style={{
            WebkitMaskImage: `linear-gradient(to bottom, black ${titleFadeStart}%, transparent ${titleFadeEnd}%)`,
            maskImage: `linear-gradient(to bottom, black ${titleFadeStart}%, transparent ${titleFadeEnd}%)`,
          }}
        >
          {title}
        </h1>
      </div>
    </header>
  );
}

// ── Component ─────────────────────────────────────────────────────────────────
export default function LaunchDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();

  const [detail, setDetail] = useState<LaunchDetail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [view, setView] = useState<'2d' | '3d'>('2d');
  const [selectedIso3, setSelectedIso3] = useState<string | null>(null);
  const [filter, setFilter] = useState<'all' | 'compliant' | 'warning' | 'noncompliant'>('all');

  // Keep a ref to detail so the interval closure can read current value
  const detailRef = useRef<LaunchDetail | null>(null);
  detailRef.current = detail;

  // Map canvas ref for ResizeObserver
  const canvasRef = useRef<HTMLDivElement>(null);
  const [mapHeight, setMapHeight] = useState<number>(
    typeof window !== 'undefined' ? Math.max(420, window.innerHeight - 240) : 520,
  );

  // Refs for row elements (for scroll-into-view on map select)
  const rowRefs = useRef<Map<string, HTMLDivElement>>(new Map());

  useEffect(() => {
    if (!id) return;
    let cancelled = false;

    const load = () =>
      getLaunch(id)
        .then((d) => { if (!cancelled) setDetail(d); })
        .catch((e) => { if (!cancelled) setError(e instanceof Error ? e.message : String(e)); });

    load();

    const t = setInterval(() => {
      const cur = detailRef.current;
      if (cur?.jurisdictions.some((r) => r.status === 'RUNNING')) {
        load();
      }
    }, 5000);

    return () => {
      cancelled = true;
      clearInterval(t);
    };
  }, [id]);

  // Measure map canvas height
  useLayoutEffect(() => {
    const el = canvasRef.current;
    if (!el) return;
    const ro = new ResizeObserver((entries) => {
      const h = entries[0].contentRect.height;
      if (h > 0) setMapHeight(h);
    });
    ro.observe(el);
    const r = el.getBoundingClientRect();
    if (r.height > 0) setMapHeight(r.height);
    return () => ro.disconnect();
  }, []);

  // ── Map data ────────────────────────────────────────────────────────────────
  const mapData = useMemo(() => {
    const m = new Map<string, { color: string; label?: string }>();
    detail?.jurisdictions.forEach((r) => {
      const iso3 = ISO2_TO_ISO3[r.jurisdictionCode] ?? r.jurisdictionCode;
      const color = r.status === 'RUNNING' ? '#444444' : verdictToHex(r.verdict);
      m.set(iso3, { color, label: jurisdictionLabel(r.jurisdictionCode) });
    });
    return m;
  }, [detail]);

  const onSelect = (iso3: string) => {
    const next = iso3 || null;
    setSelectedIso3(next);
    if (next) {
      const iso2 = ISO3_TO_ISO2[next] ?? next;
      const el = rowRefs.current.get(iso2);
      if (el) el.scrollIntoView({ behavior: 'smooth', block: 'center' });
    }
  };

  // ── Aggregate state ─────────────────────────────────────────────────────────
  const aggState: AggregateState = detail ? aggregateState(detail.jurisdictions) : null;
  const aggVerdict: Verdict =
    aggState?.kind === 'verdict' ? aggState.verdict : 'PENDING';

  const launch = detail?.launch;

  // ── Counts ──────────────────────────────────────────────────────────────────
  const counts = useMemo(() => {
    const juris = detail?.jurisdictions ?? [];
    return {
      total: juris.length,
      ok: juris.filter((r) => verdictToStatus(r.verdict) === 'compliant' && r.status !== 'RUNNING' && r.status !== 'PENDING').length,
      review: juris.filter((r) => runStatusKey(r) === 'warning').length,
      block: juris.filter((r) => runStatusKey(r) === 'noncompliant').length,
    };
  }, [detail]);

  const anyRunning = Boolean(detail?.jurisdictions.some((r) => r.status === 'RUNNING' || r.status === 'PENDING'));

  // ── Filtered rows ────────────────────────────────────────────────────────────
  const filtered = useMemo(() => {
    const juris = detail?.jurisdictions ?? [];
    if (filter === 'all') return juris;
    return juris.filter((r) => runStatusKey(r) === filter);
  }, [detail, filter]);

  // ── Stats line ───────────────────────────────────────────────────────────────
  const statsLine = anyRunning
    ? 'running…'
    : `${counts.total} jurisdictions · ${counts.ok} ok · ${counts.review} review · ${counts.block} block`;

  const createdAtShort = launch?.createdAt
    ? new Date(launch.createdAt).toLocaleDateString(undefined, { month: 'short', day: 'numeric' })
    : '';
  const createdAtFull = launch?.createdAt
    ? new Date(launch.createdAt).toLocaleString()
    : '';

  return (
    <div className="juris">

      {/* ── Left column ─────────────────────────────────────────────────────── */}
      <div className="fjp__map-wrap">
        <div className="juris__map">

          {/* Map head */}
          <div className="juris__map-head">
            <div className="juris__map-title">
              <Link to="/launches" className="juris__map-eyebrow">
                ← ALL LAUNCHES
              </Link>
              <span className="juris__map-h">{launch?.name ?? id}</span>
            </div>

            <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-end', gap: 8 }}>
              <div className="juris__legend">
                <span className="juris__legend-item">
                  <span className="juris__legend-dot" style={{ background: verdictToHex('GREEN') }} />
                  GREEN
                </span>
                <span className="juris__legend-item">
                  <span className="juris__legend-dot" style={{ background: verdictToHex('AMBER') }} />
                  AMBER
                </span>
                <span className="juris__legend-item">
                  <span className="juris__legend-dot" style={{ background: verdictToHex('RED') }} />
                  RED
                </span>
              </div>
              <div style={{ display: 'flex', gap: 2, background: 'rgba(255,255,255,0.05)', borderRadius: 999, padding: 3 }}>
                <button
                  className={`chip chip--sm${view === '2d' ? ' chip--orange' : ''}`}
                  onClick={() => setView('2d')}
                  style={{ borderRadius: 999, border: 'none', cursor: 'pointer', padding: '4px 14px' }}
                >
                  2D
                </button>
                <button
                  className={`chip chip--sm${view === '3d' ? ' chip--orange' : ''}`}
                  onClick={() => setView('3d')}
                  style={{ borderRadius: 999, border: 'none', cursor: 'pointer', padding: '4px 14px' }}
                >
                  Globe
                </button>
              </div>
            </div>
          </div>

          {/* Map canvas */}
          <div className="juris__map-canvas" ref={canvasRef}>
            {view === '2d' ? (
              <WorldMapD3
                data={mapData}
                selected={selectedIso3 ?? undefined}
                onSelect={onSelect}
                height={mapHeight}
              />
            ) : (
              <WorldMapGlobe
                data={mapData}
                selected={selectedIso3 ?? undefined}
                onSelect={onSelect}
                height={mapHeight}
              />
            )}
          </div>

          {/* Map foot */}
          <div className="juris__map-foot">
            <span style={{ color: 'var(--ink-3)', flexShrink: 0, display: 'inline-flex' }}>
              <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                <circle cx="11" cy="11" r="8" /><line x1="21" y1="21" x2="16.65" y2="16.65" />
              </svg>
            </span>
            <span style={{ flex: 1, fontSize: 12, color: 'var(--ink-3)', fontFamily: 'var(--ui)' }}>
              Select a market to inspect
            </span>
            <div className="juris__map-foot-stats">
              <span>{statsLine}</span>
            </div>
          </div>
        </div>

        {/* Brief overlay */}
        {launch && (
          <div className="fjp__brief-overlay">
            <article className="fjp__brief doccard doccard--hot">
              <div className="doccard__head">
                <span
                  style={{
                    width: 8,
                    height: 8,
                    borderRadius: '50%',
                    background: verdictToHex(aggVerdict),
                    display: 'inline-block',
                    flexShrink: 0,
                  }}
                />
                <span className="mono-label">{launch.kind ?? 'LAUNCH'}</span>
                <span className="mono-label fjp__brief-meta">
                  Launch · {createdAtShort}
                </span>
                <span style={{ marginLeft: 'auto' }}>
                  {aggState?.kind === 'verdict' && (
                    <VerdictPill verdict={aggState.verdict} />
                  )}
                  {aggState?.kind === 'running' && (
                    <span
                      className="chip chip--sm"
                      style={{ animation: 'ldPulse 1.5s ease-in-out infinite' }}
                    >
                      RUNNING
                    </span>
                  )}
                  {aggState?.kind === 'failed' && (
                    <span
                      className="chip chip--sm"
                      style={{ color: 'var(--danger, #d94a4a)', borderColor: 'rgba(217,74,74,0.3)' }}
                    >
                      FAILED
                    </span>
                  )}
                </span>
              </div>

              <div className="doccard__title fjp__brief-title">{launch.name}</div>

              {launch.brief && (
                <div className="fjp__brief-prompt">
                  <p className="fjp__brief-text">{launch.brief}</p>
                </div>
              )}

              <div className="doccard__foot fjp__brief-foot">
                <span className="mono-label">Started · {createdAtFull}</span>
                <span className="doccard__links">
                  {detail?.jurisdictions.length ?? 0} jurisdictions
                </span>
              </div>
            </article>
          </div>
        )}
      </div>

      {/* ── Right column ────────────────────────────────────────────────────── */}
      <div className="fjp">
        <Hero
          title={launch?.name ?? (id ?? '')}
          total={counts.total}
          ok={counts.ok}
          review={counts.review}
          block={counts.block}
          anyRunning={anyRunning}
        />

        <div className="fjp__body">
          {/* Error banner */}
          {error && (
            <div
              style={{
                background: 'rgba(217,74,74,0.08)',
                border: '1px solid rgba(217,74,74,0.3)',
                color: 'var(--danger, #d94a4a)',
                padding: '10px 14px',
                borderRadius: 'var(--r-md)',
              }}
            >
              {error}
            </div>
          )}

          {/* Loading state */}
          {!detail && !error && (
            <div className="juris__empty">
              <span className="juris__empty-h" style={{ animation: 'ldPulse 1.5s ease-in-out infinite' }}>
                Loading launch…
              </span>
            </div>
          )}

          {/* Filters */}
          {detail && (
            <div className="fjp__filters">
              <span className="mono-label fjp__filters-label">
                Coverage across {counts.total} jurisdictions
              </span>
              <div className="fjp__chips">
                <button
                  className={`fjp__chip${filter === 'all' ? ' fjp__chip--active' : ''}`}
                  onClick={() => setFilter('all')}
                >
                  <span>All</span>
                  <span className="fjp__chip-count">{counts.total}</span>
                </button>
                <button
                  className={`fjp__chip${filter === 'compliant' ? ' fjp__chip--active' : ''}`}
                  onClick={() => setFilter('compliant')}
                >
                  <span className="fjp__chip-dot" style={{ background: 'var(--success)' }} />
                  <span>Compliant</span>
                  <span className="fjp__chip-count">{counts.ok}</span>
                </button>
                <button
                  className={`fjp__chip${filter === 'warning' ? ' fjp__chip--active' : ''}`}
                  onClick={() => setFilter('warning')}
                >
                  <span className="fjp__chip-dot" style={{ background: 'var(--warning)' }} />
                  <span>Needs review</span>
                  <span className="fjp__chip-count">{counts.review}</span>
                </button>
                <button
                  className={`fjp__chip${filter === 'noncompliant' ? ' fjp__chip--active' : ''}`}
                  onClick={() => setFilter('noncompliant')}
                >
                  <span className="fjp__chip-dot" style={{ background: 'var(--danger)' }} />
                  <span>Breaches</span>
                  <span className="fjp__chip-count">{counts.block}</span>
                </button>
              </div>
            </div>
          )}

          {/* Rows */}
          {detail && (
            <div className="fjp__rows">
              {detail.jurisdictions.length === 0 && (
                <div className="fjp__empty">No jurisdictions yet.</div>
              )}
              {detail.jurisdictions.length > 0 && filtered.length === 0 && (
                <div className="fjp__empty">No jurisdictions match this filter.</div>
              )}
              {filtered.map((run) => {
                const code = run.jurisdictionCode;
                const iso3 = ISO2_TO_ISO3[code] ?? code;
                const isSelected = iso3 === selectedIso3;
                const key = runStatusKey(run);
                const isRunning = run.status === 'RUNNING' || run.status === 'PENDING';

                const defaultSummary = [
                  `Gaps ${run.gapsCount}`,
                  `Sanctions hits ${run.sanctionsHits}`,
                  run.lastRunAt ? `Last run ${new Date(run.lastRunAt).toLocaleDateString()}` : '',
                ].filter(Boolean).join(' · ');

                const requiredAction =
                  run.requiredChanges?.[0] ??
                  run.blockers?.[0] ??
                  'No action required';

                return (
                  <div
                    key={code}
                    className={`fjp__row fjp__row--${key} fjp__row--open`}
                    ref={(el) => {
                      if (el) rowRefs.current.set(code, el);
                      else rowRefs.current.delete(code);
                    }}
                    style={isSelected ? { borderColor: 'var(--orange)' } : undefined}
                  >
                    <div className="fjp__row-head fjp__row-head--static">
                      <span className="fjp__row-flag">{jurisdictionFlag(code)}</span>
                      <span className="fjp__row-name">{jurisdictionLabel(code)}</span>
                      <span className={`fjp__row-status fjp__row-status--${key}`}>
                        <span className="fjp__row-status-dot" />
                        {statusLabel(key, isRunning)}
                      </span>
                      <span className="fjp__row-summary">
                        {run.summary ?? defaultSummary}
                      </span>
                      <button
                        className="fjp__deselect"
                        onClick={() => navigate(`/jurisdictions/${code}/launches/${id}`)}
                        title="Open graph"
                        aria-label="Open graph"
                      >
                        <svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
                          <line x1="5" y1="12" x2="19" y2="12" />
                          <polyline points="12 5 19 12 12 19" />
                        </svg>
                      </button>
                    </div>

                    <div className="fjp__row-detail">
                      <div className="fjp__detail-grid">
                        <div className="fjp__detail-block fjp__detail-block--wide">
                          <div className="mono-label">Required action</div>
                          <div className="fjp__detail-value">{requiredAction}</div>
                        </div>
                        <div className="fjp__detail-block fjp__detail-block--wide">
                          <div className="mono-label">Stats</div>
                          <div className="fjp__detail-refs">
                            <span className="fjp__ref">Gaps {run.gapsCount}</span>
                            <span className="fjp__ref">Sanctions {run.sanctionsHits}</span>
                            {run.obligationsCovered !== undefined && run.obligationsTotal !== undefined && (
                              <span className="fjp__ref">
                                Obligations {run.obligationsCovered}/{run.obligationsTotal}
                              </span>
                            )}
                            {run.lastRunAt && (
                              <span className="fjp__ref">
                                Last run {new Date(run.lastRunAt).toLocaleDateString()}
                              </span>
                            )}
                          </div>
                        </div>
                      </div>

                      <div style={{ display: 'flex', gap: 10, marginTop: 14 }}>
                        <button
                          className="btn btn--orange btn--sm"
                          onClick={() => downloadProofPack(id!, code)}
                        >
                          Download proof pack
                        </button>
                        <button
                          className="btn btn--sm"
                          onClick={() => navigate(`/jurisdictions/${code}/launches/${id}`)}
                        >
                          Open graph →
                        </button>
                      </div>
                    </div>
                  </div>
                );
              })}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
