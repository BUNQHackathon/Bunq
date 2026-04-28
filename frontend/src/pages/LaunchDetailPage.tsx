import { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { useNavigate, useParams, Link } from 'react-router-dom';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import useJudgesGate from '../auth/useJudgesGate';
import {
  getLaunch,
  downloadProofPack,
  rerunFailedJurisdictions,
  jurisdictionFlag,
  jurisdictionLabel,
  type LaunchDetail,
  type JurisdictionRun,
  type Verdict,
  type JurisdictionStatus,
} from '../api/launch';
import { useJurisdictionStream } from '../hooks/useJurisdictionStream';
import WorldMapD3 from '../components/WorldMapD3';
import WorldMapGlobe from '../components/WorldMapGlobe';
import { verdictToHex, FAILED_COLOR } from '../components/VerdictPill';
import HeroGradient from '../components/HeroGradient';
import { ISO2_TO_ISO3, ISO3_TO_ISO2, MOCK_COUNTRY_COLOR, MOCK_COUNTRY_LABEL } from '../api/mockCountries';

// ── Aggregate verdict (worst of children) ────────────────────────────────────
const VERDICT_RANK: Record<Verdict, number> = { GREEN: 0, AMBER: 1, RED: 2, PENDING: -1, UNKNOWN: -1 };

function worstVerdict(rs: { verdict: Verdict }[]): Verdict | null {
  const valid = rs.filter((r) => VERDICT_RANK[r.verdict] >= 0);
  if (valid.length === 0) {
    if (rs.length === 0) return null;
    if (rs.every((r) => r.verdict === 'UNKNOWN')) return 'UNKNOWN';
    return 'PENDING';
  }
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
type StatusKey = 'compliant' | 'warning' | 'noncompliant' | 'failed' | 'inprogress' | 'unknown';

function verdictToStatus(v: Verdict): StatusKey {
  if (v === 'GREEN') return 'compliant';
  if (v === 'RED') return 'noncompliant';
  if (v === 'UNKNOWN') return 'unknown';
  return 'warning';
}

function statusLabelForRun(run: JurisdictionRun, key: StatusKey, isRunning: boolean): string {
  if (isRunning) return 'In progress';
  if (key === 'failed') return 'Failed';
  if (key === 'inprogress') return 'In progress';
  if (key === 'unknown') return 'Unknown';
  if (run.verdict === 'UNKNOWN') return 'Unknown';
  if (key === 'compliant') return 'Compliant';
  if (key === 'warning') return 'Needs review';
  return 'Breach';
}

function runStatusKey(run: JurisdictionRun): StatusKey {
  if (run.status === 'FAILED') return 'failed';
  if (run.status === 'RUNNING' || run.status === 'PENDING') return 'inprogress';
  return verdictToStatus(run.verdict);
}


function statusTooltip(run: JurisdictionRun, key: StatusKey, isRunning: boolean): string {
  if (key === 'failed') return 'Pipeline error (rate limit, timeout, etc.). Couldn\'t determine compliance — retry the run.';
  if (key === 'inprogress' || isRunning) return 'Analysis in progress.';
  if (key === 'unknown') return 'Verdict: UNKNOWN — analysis returned an indeterminate result.';
  if (key === 'compliant') return 'Verdict: GREEN — can ship as-is.';
  if (key === 'noncompliant') return 'Verdict: RED — regulatory blocker. Cannot ship in this jurisdiction.';
  if (run.verdict === 'AMBER') return 'Verdict: AMBER — required changes before shipping.';
  return 'Analysis in progress.';
}

// ── Hero component (inline) ───────────────────────────────────────────────────
interface HeroProps {
  title: string;
  total: number;
  ok: number;
  review: number;
  block: number;
  failed: number;
  anyRunning: boolean;
  animate?: boolean;
}

function Hero({ title, total, ok, review, block, failed, anyRunning, animate }: HeroProps) {
  const headerHeight = 220;
  const bgColor = '#0b0a09';
  const titleFadeStart = 15;
  const titleFadeEnd = 115;

  return (
    <header
      className="juris__hero"
      style={{ minHeight: `${headerHeight}px`, background: bgColor }}
    >
      <HeroGradient animate={animate} />

      <div
        style={{
          position: 'relative',
          zIndex: 2,
          textAlign: 'left',
          padding: '10px 32px 24px',
        }}
      >
        <div className="juris__hero-eyebrow">
          <span className="mono-label" style={{ color: '#D8AC78' }}>
            LAUNCH BREAKDOWN
          </span>
          <span className="mono-label" style={{ color: 'rgba(255,255,255,0.45)' }}>
            {anyRunning
              ? `· ${total} JURISDICTIONS · RUNNING`
              : `· ${total} JURISDICTIONS · ${ok} OK · ${review} REVIEW · ${block} BLOCK${failed > 0 ? ` · ${failed} FAILED` : ''}`}
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

// ── Live stage indicator (additive, shown only when RUNNING + SSE active) ─────
function JurisdictionLiveIndicator({
  launchId,
  code,
  onDone,
}: {
  launchId: string;
  code: string;
  onDone: () => void;
}) {
  const { currentStage, status, lastEvent } = useJurisdictionStream(launchId, code, { onDone });

  if (status === 'idle' || status === 'closed') return null;

  const ordinal = lastEvent?.type === 'stage.started' ? lastEvent.ordinal : undefined;
  const total = lastEvent?.type === 'stage.started' ? lastEvent.totalStages : undefined;
  const label = currentStage
    ? `${currentStage}${ordinal != null && total != null ? ` ${ordinal}/${total}` : ''}`
    : status === 'connecting'
      ? 'Connecting…'
      : null;

  if (!label) return null;

  return (
    <span
      className="mono-label"
      style={{
        marginTop: 6,
        display: 'inline-flex',
        alignItems: 'center',
        gap: 6,
        fontSize: 11,
        color: 'var(--ink-2)',
      }}
    >
      <span style={{ animation: 'ldPulse 1s ease-in-out infinite', display: 'inline-block' }}>▶</span>
      {label}
    </span>
  );
}

// ── Component ─────────────────────────────────────────────────────────────────
export default function LaunchDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const { requireJudge, modal } = useJudgesGate();

  const [detail, setDetail] = useState<LaunchDetail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [view, setView] = useState<'2d' | '3d'>('2d');
  const [selectedIso3, setSelectedIso3] = useState<string | null>(null);
  const [retrying, setRetrying] = useState(false);
  const [filter, setFilter] = useState<'all' | 'compliant' | 'warning' | 'noncompliant' | 'unknown' | 'inprogress'>('all');

  // Keep a ref to detail so the interval closure can read current value
  const detailRef = useRef<LaunchDetail | null>(null);
  detailRef.current = detail;

  // Exposed so SSE onDone can trigger a refetch
  const loadRef = useRef<(() => void) | null>(null);
  const refetch = () => loadRef.current?.();

  // Map canvas ref for ResizeObserver
  const canvasRef = useRef<HTMLDivElement>(null);
  const [mapHeight, setMapHeight] = useState<number>(
    typeof window !== 'undefined' ? Math.max(420, window.innerHeight - 48) : 520,
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

    loadRef.current = load;

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
      loadRef.current = null;
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
    // 1. Demo overlay first — keeps the globe alive even when this launch
    //    only covers a few countries.
    for (const [iso3, color] of Object.entries(MOCK_COUNTRY_COLOR)) {
      m.set(iso3, { color, label: MOCK_COUNTRY_LABEL[iso3] ?? iso3 });
    }
    // 2. Real launch verdicts overwrite the overlay where present.
    detail?.jurisdictions.forEach((r) => {
      const iso3 = ISO2_TO_ISO3[r.jurisdictionCode] ?? r.jurisdictionCode;
      // Grey-state routing:
      // - RUNNING/PENDING (status) → solid grey (FAILED_COLOR) — labeled "In progress" in the legend
      // - UNKNOWN verdict          → '#444444' sentinel (stripe pattern)  — labeled "Unknown" in the legend
      // - FAILED status falls through to verdictToHex(r.verdict); if its verdict is UNKNOWN it gets stripes,
      //   otherwise it shows the verdict color it landed on before the failure.
      const isInProgress = r.status === 'RUNNING' || r.status === 'PENDING';
      const color = isInProgress
        ? FAILED_COLOR
        : r.verdict === 'UNKNOWN'
          ? '#444444'
          : verdictToHex(r.verdict);
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

  const hasFailed = detail?.jurisdictions.some((r) => r.status === 'FAILED') ?? false;

  const handleRerunFailed = () => {
    if (!id || retrying) return;
    setRetrying(true);
    rerunFailedJurisdictions(id)
      .then(() => { refetch(); })
      .catch(() => { })
      .finally(() => { setRetrying(false); });
  };

  const launch = detail?.launch;

  // ── Counts ──────────────────────────────────────────────────────────────────
  const counts = useMemo(() => {
    const juris = detail?.jurisdictions ?? [];
    return {
      total: juris.length,
      ok: juris.filter((r) => verdictToStatus(r.verdict) === 'compliant' && r.status !== 'RUNNING' && r.status !== 'PENDING').length,
      review: juris.filter((r) => runStatusKey(r) === 'warning').length,
      block: juris.filter((r) => runStatusKey(r) === 'noncompliant').length,
      unknown: juris.filter((r) => runStatusKey(r) === 'unknown').length,
      inprogress: juris.filter((r) => runStatusKey(r) === 'inprogress').length,
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
      {modal}
      {/* ── Left column ─────────────────────────────────────────────────────── */}
      <div className="fjp__map-wrap">
        <div className="juris__map">

          {/* Map head */}
          <div className="juris__map-head">
            <div className="juris__map-title">
              <Link to="/launches" className="juris__map-eyebrow">
                ← ALL LAUNCHES
              </Link>
              <span className="juris__map-h">{launch?.name ?? ' '}</span>
            </div>

            <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-end', gap: 8 }}>
              <div className="juris__legend">
                <span className="juris__legend-item">
                  <span className="juris__legend-dot" style={{ background: verdictToHex('GREEN') }} />
                  Compliant
                </span>
                <span className="juris__legend-item">
                  <span className="juris__legend-dot" style={{ background: 'repeating-linear-gradient(45deg, #cfb275 0 2px, #a83820 2px 4px)' }} />
                  Needs review
                </span>
                <span className="juris__legend-item">
                  <span className="juris__legend-dot" style={{ background: verdictToHex('RED') }} />
                  Breach
                </span>
                <span className="juris__legend-item">
                  <span className="juris__legend-dot" style={{ background: 'repeating-linear-gradient(45deg, #6b6b6b 0 2px, #9a9a9a 2px 4px)' }} />
                  Unknown
                </span>
                <span className="juris__legend-item">
                  <span className="juris__legend-dot" style={{ background: FAILED_COLOR }} />
                  In progress
                </span>
              </div>
              <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
                {/* Retry failed button — visible only when hasFailed */}
                {hasFailed && (
                  <button
                    className="btn btn--sm"
                    disabled={retrying}
                    onClick={requireJudge(handleRerunFailed)}
                  >
                    {retrying ? 'Retrying…' : 'Retry failed'}
                  </button>
                )}
                <div style={{ display: 'flex', gap: 2, background: 'rgba(20, 17, 16, 0.78)', backdropFilter: 'blur(8px)', border: '1px solid var(--line-0)', borderRadius: 999, padding: 3 }}>
                  <button
                    className="chip chip--sm"
                    onClick={() => setView('2d')}
                    style={{
                      background: view === '2d' ? 'var(--orange-wash)' : 'transparent',
                      color: view === '2d' ? 'var(--orange)' : 'var(--ink-2)',
                      border: view === '2d' ? '1px solid rgba(239,106,42,0.3)' : '1px solid transparent',
                      borderRadius: 999,
                      cursor: 'pointer',
                      padding: '4px 14px',
                      fontWeight: view === '2d' ? 600 : 400,
                    }}
                  >
                    2D
                  </button>
                  <button
                    className="chip chip--sm"
                    onClick={() => setView('3d')}
                    style={{
                      background: view === '3d' ? 'var(--orange-wash)' : 'transparent',
                      color: view === '3d' ? 'var(--orange)' : 'var(--ink-2)',
                      border: view === '3d' ? '1px solid rgba(239,106,42,0.3)' : '1px solid transparent',
                      borderRadius: 999,
                      cursor: 'pointer',
                      padding: '4px 14px',
                      fontWeight: view === '3d' ? 600 : 400,
                    }}
                  >
                    Globe
                  </button>
                </div>
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
            <article className="fjp__brief doccard doccard--hot glow-behind">
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
                  {aggState && (() => {
                    const aggKey: StatusKey =
                      aggState.kind === 'running' ? 'inprogress' :
                        aggState.kind === 'failed' ? 'failed' :
                          verdictToStatus(aggState.verdict);
                    const aggLabel =
                      aggKey === 'inprogress' ? 'In progress' :
                        aggKey === 'failed' ? 'Failed' :
                          aggKey === 'compliant' ? 'Compliant' :
                            aggKey === 'warning' ? 'Needs review' :
                              aggKey === 'noncompliant' ? 'Breach' :
                                'Unknown';
                    return (
                      <span className={`fjp__row-status fjp__row-status--${aggKey}`}>
                        <span className="fjp__row-status-dot" />
                        {aggLabel}
                      </span>
                    );
                  })()}
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
          key={id}
          title={launch?.name ?? ' '}
          total={counts.total}
          ok={counts.ok}
          review={counts.review}
          block={counts.block}
          failed={(detail?.jurisdictions ?? []).filter((r) => runStatusKey(r) === 'failed').length}
          anyRunning={anyRunning}
          animate
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
                  <span className="fjp__chip-dot" style={{ background: '#cfb275' }} />
                  <span>Compliant</span>
                  <span className="fjp__chip-count">{counts.ok}</span>
                </button>
                <button
                  className={`fjp__chip${filter === 'warning' ? ' fjp__chip--active' : ''}`}
                  onClick={() => setFilter('warning')}
                >
                  <span className="fjp__chip-dot" style={{ background: 'repeating-linear-gradient(45deg, #cfb275 0 2px, #a83820 2px 4px)' }} />
                  <span>Needs review</span>
                  <span className="fjp__chip-count">{counts.review}</span>
                </button>
                <button
                  className={`fjp__chip${filter === 'noncompliant' ? ' fjp__chip--active' : ''}`}
                  onClick={() => setFilter('noncompliant')}
                >
                  <span className="fjp__chip-dot" style={{ background: '#a83820' }} />
                  <span>Breach</span>
                  <span className="fjp__chip-count">{counts.block}</span>
                </button>
                <button
                  className={`fjp__chip${filter === 'unknown' ? ' fjp__chip--active' : ''}`}
                  onClick={() => setFilter('unknown')}
                >
                  <span className="fjp__chip-dot" style={{ background: 'repeating-linear-gradient(45deg, #6b6b6b 0 2px, #9a9a9a 2px 4px)' }} />
                  <span>Unknown</span>
                  <span className="fjp__chip-count">{counts.unknown}</span>
                </button>
                <button
                  className={`fjp__chip${filter === 'inprogress' ? ' fjp__chip--active' : ''}`}
                  onClick={() => setFilter('inprogress')}
                >
                  <span className="fjp__chip-dot" style={{ background: FAILED_COLOR }} />
                  <span>In progress</span>
                  <span className="fjp__chip-count">{counts.inprogress}</span>
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

                const countsLine = `Obligations: ${run.obligationsCount ?? 0} • Controls: ${run.controlsCount ?? 0} • Gaps: ${run.gapsCount ?? 0}`;

                const actionItems =
                  run.verdict === 'RED'
                    ? (run.blockers ?? []).slice(0, 5)
                    : (run.requiredChanges ?? []).slice(0, 5);

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
                      <span
                        className={`fjp__row-status fjp__row-status--${key}`}
                        title={statusTooltip(run, key, isRunning)}
                      >
                        <span className="fjp__row-status-dot" />
                        {/* StripLiveLabel replaces static "In progress" with live SSE stage name */}
                        {isRunning
                          ? <StripLiveLabel launchId={id!} code={code} onDone={refetch} />
                          : statusLabelForRun(run, key, false)}
                      </span>
                      <span className="fjp__row-summary" style={{ display: 'inline-flex', flexDirection: 'column', alignItems: 'flex-start', gap: 2 }}>
                        <span>{run.summary ?? defaultSummary}</span>
                        <span style={{ fontSize: 11, color: 'rgba(255,255,255,0.55)', fontFamily: 'var(--mono)' }}>
                          {countsLine}
                        </span>
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
                          <div className="mono-label">Compliance summary</div>
                          {run.summary && (
                            <div className="fjp__detail-value" style={{ marginBottom: actionItems.length > 0 ? 10 : 0 }}>
                              <ReactMarkdown
                                remarkPlugins={[remarkGfm]}
                                components={{
                                  p: ({ children }) => <p style={{ margin: '0 0 6px 0' }}>{children}</p>,
                                  ul: ({ children }) => <ul style={{ margin: '4px 0', paddingLeft: 16, listStyle: 'disc' }}>{children}</ul>,
                                  ol: ({ children }) => <ol style={{ margin: '4px 0', paddingLeft: 18 }}>{children}</ol>,
                                  li: ({ children }) => <li style={{ marginBottom: 2 }}>{children}</li>,
                                  strong: ({ children }) => <strong style={{ fontWeight: 600 }}>{children}</strong>,
                                  em: ({ children }) => <em>{children}</em>,
                                  code: ({ className, children }) =>
                                    className?.startsWith('language-') ? (
                                      <pre style={{ margin: '6px 0', padding: 8, background: 'rgba(0,0,0,0.3)', borderRadius: 6, overflowX: 'auto', fontSize: 12 }}><code>{children}</code></pre>
                                    ) : (
                                      <code style={{ padding: '1px 4px', background: 'rgba(255,255,255,0.08)', borderRadius: 4, fontSize: 12 }}>{children}</code>
                                    ),
                                  a: ({ href, children }) => <a href={href} target="_blank" rel="noreferrer" style={{ color: '#fb923c', textDecoration: 'underline' }}>{children}</a>,
                                  h1: ({ children }) => <div style={{ fontWeight: 600, fontSize: 14, margin: '6px 0 4px' }}>{children}</div>,
                                  h2: ({ children }) => <div style={{ fontWeight: 600, fontSize: 14, margin: '6px 0 4px' }}>{children}</div>,
                                  h3: ({ children }) => <div style={{ fontWeight: 600, fontSize: 14, margin: '6px 0 4px' }}>{children}</div>,
                                  hr: () => <hr style={{ margin: '6px 0', border: 'none', borderTop: '1px solid rgba(255,255,255,0.1)' }} />,
                                }}
                              >
                                {run.summary}
                              </ReactMarkdown>
                            </div>
                          )}
                          {actionItems.length > 0 ? (
                            <ul style={{ margin: 0, padding: '0 0 0 16px', listStyle: 'disc' }}>
                              {actionItems.map((item, i) => (
                                <li key={i} className="fjp__detail-value" style={{ marginBottom: 2 }}>
                                  {item}
                                </li>
                              ))}
                            </ul>
                          ) : !run.summary ? (
                            <div className="fjp__detail-value">No action required</div>
                          ) : null}
                        </div>
                        <div className="fjp__detail-block fjp__detail-block--wide">
                          <div className="mono-label">Stats</div>
                          <div className="fjp__detail-refs">
                            <span className="fjp__ref">Obligations {run.obligationsCount ?? 0}</span>
                            <span className="fjp__ref">Controls {run.controlsCount ?? 0}</span>
                            <span className="fjp__ref">Gaps {run.gapsCount}</span>
                            <span className="fjp__ref">Sanctions {run.sanctionsHits}</span>
                            {run.obligationsCovered !== undefined && run.obligationsTotal !== undefined && (
                              <span className="fjp__ref">
                                Coverage {run.obligationsCovered}/{run.obligationsTotal}
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

                      {/* JurisdictionLiveIndicator — shown in row detail when RUNNING */}
                      {isRunning && id && (
                        <div style={{ marginTop: 10 }}>
                          <JurisdictionLiveIndicator
                            launchId={id}
                            code={code}
                            onDone={refetch}
                          />
                        </div>
                      )}

                      <div style={{ display: 'flex', gap: 10, marginTop: 14 }}>
                        <button
                          className="btn btn--orange-hollow btn--sm"
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

// ── Strip chip live label (shows current stage or "In progress" fallback) ────────
function StripLiveLabel({ launchId, code, onDone }: { launchId: string; code: string; onDone: () => void }) {
  const { currentStage, lastEvent } = useJurisdictionStream(launchId, code, { onDone });
  const ordinal = lastEvent?.type === 'stage.started' ? lastEvent.ordinal : undefined;
  const total = lastEvent?.type === 'stage.started' ? lastEvent.totalStages : undefined;
  const label = currentStage
    ? `${currentStage}${ordinal != null && total != null ? ` ${ordinal}/${total}` : ''}`
    : 'In progress';
  return (
    <span className="mono-label" style={{ animation: 'ldPulse 1.5s ease-in-out infinite' }}>
      {label}
    </span>
  );
}
