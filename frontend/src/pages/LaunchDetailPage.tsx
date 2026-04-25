import { useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate, useParams, Link } from 'react-router-dom';
import {
  getLaunch,
  downloadProofPack,
  jurisdictionFlag,
  jurisdictionLabel,
  type LaunchDetail,
  type Verdict,
  type JurisdictionStatus,
} from '../api/launch';
import WorldMapD3 from '../components/WorldMapD3';
import WorldMapGlobe from '../components/WorldMapGlobe';
import VerdictPill, { verdictToHex } from '../components/VerdictPill';
import KindBadge from '../components/KindBadge';

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

// ── Component ─────────────────────────────────────────────────────────────────
export default function LaunchDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();

  const [detail, setDetail] = useState<LaunchDetail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [view, setView] = useState<'2d' | '3d'>('2d');
  const [selectedIso3, setSelectedIso3] = useState<string | null>(null);

  // Keep a ref to detail so the interval closure can read current value
  const detailRef = useRef<LaunchDetail | null>(null);
  detailRef.current = detail;

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

  const onSelect = (iso3: string) => setSelectedIso3(iso3 || null);

  // ── Aggregate state ─────────────────────────────────────────────────────────
  const aggState: AggregateState = detail ? aggregateState(detail.jurisdictions) : null;

  // ── Selected jurisdiction run ───────────────────────────────────────────────
  const selectedIso2 = selectedIso3 ? (ISO3_TO_ISO2[selectedIso3] ?? selectedIso3) : null;
  const selectedRun = selectedIso2
    ? detail?.jurisdictions.find((r) => r.jurisdictionCode === selectedIso2) ?? null
    : null;

  // ── Loading / error states ──────────────────────────────────────────────────
  if (!detail && !error) {
    return (
      <div style={{ padding: '40px 60px 60px', minHeight: '100%', fontFamily: 'var(--ui)' }}>
        <div
          style={{
            background: 'var(--bg-1)',
            border: '1px solid var(--line-1)',
            borderRadius: 'var(--r-lg)',
            padding: '48px 24px',
            textAlign: 'center',
          }}
        >
          <span className="mono-label" style={{ animation: 'ldPulse 1.5s ease-in-out infinite' }}>
            Loading launch…
          </span>
        </div>
      </div>
    );
  }

  const launch = detail?.launch;

  return (
    <div style={{ padding: '40px 60px 60px', minHeight: '100%', fontFamily: 'var(--ui)' }}>

      {/* Error banner */}
      {error && (
        <div
          style={{
            background: 'rgba(217,74,74,0.08)',
            border: '1px solid rgba(217,74,74,0.3)',
            color: 'var(--danger, #d94a4a)',
            padding: '10px 14px',
            borderRadius: 'var(--r-md)',
            marginBottom: 16,
          }}
        >
          {error}
        </div>
      )}

      {/* ── Header ─────────────────────────────────────────────────────────── */}
      <div style={{ display: 'flex', alignItems: 'baseline', justifyContent: 'space-between', marginBottom: 32 }}>
        <div>
          <Link
            to="/launches"
            className="mono-label"
            style={{ textDecoration: 'none', marginBottom: 10, display: 'inline-block' }}
          >
            ← All launches
          </Link>
          <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginTop: 4 }}>
            {launch?.kind && <KindBadge kind={launch.kind} />}
            <h1 className="serif-display" style={{ fontSize: 44, margin: 0 }}>
              {launch?.name ?? id}
            </h1>
            {aggState?.kind === 'verdict' && <VerdictPill verdict={aggState.verdict} />}
            {aggState?.kind === 'running' && (
              <span className="chip chip--sm" style={{ animation: 'ldPulse 1.5s ease-in-out infinite' }}>
                RUNNING
              </span>
            )}
            {aggState?.kind === 'failed' && (
              <span className="chip chip--sm" style={{ color: 'var(--danger, #d94a4a)', borderColor: 'rgba(217,74,74,0.3)' }}>
                FAILED
              </span>
            )}
          </div>
          {launch?.brief && (
            <p style={{ color: 'var(--ink-2)', marginTop: 12, maxWidth: 640, fontSize: 15, margin: '12px 0 0' }}>
              {launch.brief}
            </p>
          )}
        </div>

        {/* 2D / 3D segmented toggle */}
        <div style={{ display: 'flex', gap: 8 }}>
          <button
            className={`chip chip--sm${view === '2d' ? ' chip--orange' : ''}`}
            onClick={() => setView('2d')}
          >
            2D
          </button>
          <button
            className={`chip chip--sm${view === '3d' ? ' chip--orange' : ''}`}
            onClick={() => setView('3d')}
          >
            Globe
          </button>
        </div>
      </div>

      {/* ── Map + Drawer ───────────────────────────────────────────────────── */}
      <div style={{ display: 'flex', gap: 16, alignItems: 'flex-start' }}>

        {/* Map card */}
        <div
          className="glow-behind"
          style={{
            flex: 1,
            minWidth: 0,
            background: 'var(--bg-1)',
            border: '1px solid var(--line-1)',
            borderRadius: 'var(--r-lg)',
            padding: 24,
            height: 560,
            overflow: 'hidden',
          }}
        >
          {view === '2d' ? (
            <WorldMapD3
              data={mapData}
              selected={selectedIso3 ?? undefined}
              onSelect={onSelect}
              height={520}
            />
          ) : (
            <WorldMapGlobe
              data={mapData}
              selected={selectedIso3 ?? undefined}
              onSelect={onSelect}
              height={520}
            />
          )}
        </div>

        {/* Drawer — only when a country is selected */}
        {selectedRun && selectedIso2 && (
          <aside
            style={{
              background: 'var(--bg-1)',
              border: '1px solid var(--line-1)',
              borderRadius: 'var(--r-lg)',
              padding: 24,
              width: 360,
              flex: 'none',
            }}
          >
            {/* Drawer header */}
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 12 }}>
              <div className="mono-label">
                {jurisdictionFlag(selectedIso2)} {jurisdictionLabel(selectedIso2)}
              </div>
              <button
                onClick={() => setSelectedIso3(null)}
                style={{
                  background: 'none',
                  border: 'none',
                  cursor: 'pointer',
                  color: 'var(--ink-2)',
                  fontSize: 18,
                  lineHeight: 1,
                  padding: 0,
                }}
                aria-label="Close"
              >
                ×
              </button>
            </div>

            <h3 className="serif-display" style={{ fontSize: 24, margin: 0, marginBottom: 16 }}>
              {jurisdictionLabel(selectedIso2)}
            </h3>

            {selectedRun.status === 'COMPLETE' && <VerdictPill verdict={selectedRun.verdict} />}

            {selectedRun.status === 'RUNNING' && (
              <span
                className="chip chip--sm"
                style={{ marginTop: 10, display: 'inline-flex', animation: 'ldPulse 1.5s ease-in-out infinite' }}
              >
                Running…
              </span>
            )}
            {selectedRun.status === 'FAILED' && (
              <span
                className="chip chip--sm"
                style={{ marginTop: 10, display: 'inline-flex', color: 'var(--danger, #d94a4a)', borderColor: 'rgba(217,74,74,0.3)' }}
              >
                Run failed
              </span>
            )}

            {/* Stats */}
            <div
              style={{
                background: 'var(--bg-2)',
                border: '1px solid var(--line-0)',
                borderRadius: 'var(--r-md)',
                padding: '12px 14px',
                marginTop: 16,
                display: 'flex',
                flexDirection: 'column',
                gap: 8,
              }}
            >
              <StatRow label="Gaps" value={String(selectedRun.gapsCount)} />
              <StatRow label="Sanctions hits" value={String(selectedRun.sanctionsHits)} />
              {selectedRun.obligationsCovered !== undefined && selectedRun.obligationsTotal !== undefined && (
                <StatRow
                  label="Obligations"
                  value={`${selectedRun.obligationsCovered} / ${selectedRun.obligationsTotal}`}
                />
              )}
              {selectedRun.lastRunAt && (
                <StatRow
                  label="Last run"
                  value={new Date(selectedRun.lastRunAt).toLocaleDateString()}
                />
              )}
            </div>

            {/* Actions */}
            <div style={{ display: 'flex', gap: 10, marginTop: 20 }}>
              <button
                className="btn btn--orange btn--sm"
                onClick={() => downloadProofPack(id!, selectedIso2)}
              >
                Download proof pack
              </button>
              <button
                className="btn btn--sm"
                onClick={() => navigate(`/jurisdictions/${selectedIso2}/launches/${id}`)}
              >
                Open graph →
              </button>
            </div>
          </aside>
        )}

        {/* Placeholder hint when no country selected */}
        {!selectedRun && detail && detail.jurisdictions.length > 0 && (
          <div
            style={{
              background: 'var(--bg-1)',
              border: '1px solid var(--line-1)',
              borderRadius: 'var(--r-lg)',
              padding: 24,
              width: 360,
              flex: 'none',
              height: 180,
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
            }}
          >
            <span className="mono-label" style={{ textAlign: 'center' }}>
              Click a country on the map
              <br />to view details
            </span>
          </div>
        )}
      </div>

      {/* ── Jurisdiction strip ─────────────────────────────────────────────── */}
      {detail && detail.jurisdictions.length > 0 && (
        <div style={{ marginTop: 24, display: 'flex', flexWrap: 'wrap', gap: 8 }}>
          {detail.jurisdictions.map((j) => {
            const iso3 = ISO2_TO_ISO3[j.jurisdictionCode] ?? j.jurisdictionCode;
            const isSelected = iso3 === selectedIso3;
            return (
              <button
                key={j.jurisdictionCode}
                onClick={() => setSelectedIso3(isSelected ? null : iso3)}
                className={`chip${isSelected ? ' chip--orange' : ''}`}
                style={{ cursor: 'pointer' }}
              >
                <span>{jurisdictionFlag(j.jurisdictionCode)}</span>
                <span className="mono-label" style={{ letterSpacing: '0.04em' }}>
                  {j.jurisdictionCode}
                </span>
                {j.status === 'COMPLETE' ? (
                  <VerdictPill verdict={j.verdict} showEmoji={false} />
                ) : j.status === 'FAILED' ? (
                  <span className="mono-label" style={{ color: 'var(--danger, #d94a4a)' }}>FAILED</span>
                ) : (
                  <span className="mono-label" style={{ animation: 'ldPulse 1.5s ease-in-out infinite' }}>RUNNING</span>
                )}
              </button>
            );
          })}
        </div>
      )}
    </div>
  );
}

// ── StatRow helper ────────────────────────────────────────────────────────────
function StatRow({ label, value }: { label: string; value: string }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8 }}>
      <span className="mono-label">{label}</span>
      <span style={{ fontFamily: 'var(--mono)', fontSize: 12, color: 'var(--ink-0)' }}>{value}</span>
    </div>
  );
}
