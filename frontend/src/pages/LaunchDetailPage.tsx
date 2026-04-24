import { useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate, useParams, Link } from 'react-router-dom';
import {
  getLaunch,
  downloadProofPack,
  jurisdictionFlag,
  jurisdictionLabel,
  type LaunchDetail,
  type Verdict,
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

  // ── Aggregate verdict ───────────────────────────────────────────────────────
  const aggVerdict = detail
    ? (detail.launch.aggregateVerdict ?? worstVerdict(detail.jurisdictions))
    : null;

  // ── Selected jurisdiction run ───────────────────────────────────────────────
  const selectedIso2 = selectedIso3 ? (ISO3_TO_ISO2[selectedIso3] ?? selectedIso3) : null;
  const selectedRun = selectedIso2
    ? detail?.jurisdictions.find((r) => r.jurisdictionCode === selectedIso2) ?? null
    : null;

  // ── Loading / error states ──────────────────────────────────────────────────
  const cardStyle: React.CSSProperties = {
    background: '#0D0D0D',
    border: '1px solid rgba(255,255,255,0.06)',
    borderRadius: 16,
  };

  if (!detail && !error) {
    return (
      <div className="min-h-screen px-6 py-10 max-w-7xl mx-auto" style={{ color: '#E8E8E8' }}>
        <div style={{ ...cardStyle, padding: '48px 24px', textAlign: 'center' }}>
          <p className="font-mono text-[13px] animate-pulse" style={{ color: '#6B6B6B' }}>
            Loading launch…
          </p>
        </div>
      </div>
    );
  }

  return (
    <div className="min-h-screen px-6 py-6 max-w-7xl mx-auto" style={{ color: '#E8E8E8' }}>

      {/* Error banner */}
      {error && (
        <div
          className="rounded-xl px-6 py-4 mb-4"
          style={{ background: 'rgba(224,80,80,0.08)', border: '1px solid rgba(224,80,80,0.25)' }}
        >
          <p className="text-[13px]" style={{ color: '#E05050' }}>{error}</p>
        </div>
      )}

      {/* ── Header ─────────────────────────────────────────────────────────── */}
      <div className="mb-6">
        <Link
          to="/launches"
          className="inline-flex items-center gap-1 text-[12px] font-mono mb-4 transition-opacity hover:opacity-70"
          style={{ color: '#6B6B6B', textDecoration: 'none' }}
        >
          ← Back to launches
        </Link>

        <div className="flex items-start justify-between gap-4 flex-wrap">
          <div className="flex-1 min-w-0">
            <div className="flex items-center gap-3 flex-wrap mb-1">
              {detail?.launch.kind && <KindBadge kind={detail.launch.kind} />}
              <h1 className="text-[22px] font-semibold text-white leading-tight">
                {detail?.launch.name ?? id}
              </h1>
              {aggVerdict && <VerdictPill verdict={aggVerdict} />}
            </div>
            {detail?.launch.brief && (
              <p className="text-[13px]" style={{ color: 'rgba(255,255,255,0.45)' }}>
                {detail.launch.brief}
              </p>
            )}
          </div>
        </div>
      </div>

      {/* ── 2D / 3D Toggle ────────────────────────────────────────────────── */}
      <div className="flex gap-1 mb-4">
        {(['2d', '3d'] as const).map((v) => (
          <button
            key={v}
            onClick={() => setView(v)}
            className="px-3 py-1 text-[11px] font-mono uppercase rounded-lg transition-all"
            style={
              view === v
                ? {
                    background: 'rgba(255,120,25,0.18)',
                    border: '1px solid rgba(255,120,25,0.4)',
                    color: '#FF9F55',
                  }
                : {
                    background: 'rgba(255,255,255,0.04)',
                    border: '1px solid rgba(255,255,255,0.08)',
                    color: '#6B6B6B',
                  }
            }
          >
            {v}
          </button>
        ))}
      </div>

      {/* ── Map + Drawer ───────────────────────────────────────────────────── */}
      <div className="flex gap-4 items-start">

        {/* Map */}
        <div
          className="flex-1 min-w-0 rounded-xl overflow-hidden"
          style={{ ...cardStyle, flexBasis: '65%' }}
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
          <div
            className="rounded-xl p-5 flex flex-col gap-4"
            style={{
              ...cardStyle,
              width: '30%',
              minWidth: 240,
              flexShrink: 0,
            }}
          >
            {/* Drawer header */}
            <div className="flex items-center justify-between gap-2">
              <div className="flex items-center gap-2">
                <span className="text-[22px]">{jurisdictionFlag(selectedIso2)}</span>
                <span className="text-[15px] font-semibold text-white">
                  {jurisdictionLabel(selectedIso2)}
                </span>
              </div>
              <button
                onClick={() => setSelectedIso3(null)}
                className="text-[18px] leading-none transition-opacity hover:opacity-60"
                style={{ color: '#6B6B6B', background: 'none', border: 'none', cursor: 'pointer' }}
                aria-label="Close"
              >
                ×
              </button>
            </div>

            {/* Verdict + status */}
            <div className="flex flex-col gap-2">
              <VerdictPill verdict={selectedRun.verdict} />
              {selectedRun.status === 'RUNNING' && (
                <p
                  className="text-[12px] font-mono"
                  style={{
                    color: '#6B6B6B',
                    animation: 'ldPulse 1.5s ease-in-out infinite',
                  }}
                >
                  Compliance run in progress…
                </p>
              )}
              {selectedRun.status === 'FAILED' && (
                <p className="text-[12px] font-mono" style={{ color: '#E05050' }}>
                  Run failed
                </p>
              )}
            </div>

            {/* Stats */}
            <div
              className="rounded-lg p-3 flex flex-col gap-2"
              style={{ background: 'rgba(255,255,255,0.03)', border: '1px solid rgba(255,255,255,0.06)' }}
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
            <div className="flex flex-col gap-2 mt-1">
              <button
                onClick={() => downloadProofPack(id!, selectedIso2)}
                className="w-full rounded-lg py-2 text-[12px] font-medium transition-all"
                style={{
                  background: 'rgba(255,120,25,0.14)',
                  border: '1px solid rgba(255,120,25,0.35)',
                  color: '#FF9F55',
                  cursor: 'pointer',
                }}
              >
                Download Proof Pack
              </button>
              <button
                onClick={() => navigate(`/jurisdictions/${selectedIso2}/launches/${id}`)}
                className="w-full rounded-lg py-2 text-[12px] font-medium transition-all"
                style={{
                  background: 'rgba(255,255,255,0.04)',
                  border: '1px solid rgba(255,255,255,0.1)',
                  color: '#E8E8E8',
                  cursor: 'pointer',
                }}
              >
                View compliance graph
              </button>
            </div>
          </div>
        )}

        {/* Placeholder hint when no country selected */}
        {!selectedRun && detail && detail.jurisdictions.length > 0 && (
          <div
            className="rounded-xl p-5 flex items-center justify-center"
            style={{
              ...cardStyle,
              width: '30%',
              minWidth: 240,
              height: 180,
              flexShrink: 0,
            }}
          >
            <p className="text-[12px] font-mono text-center" style={{ color: '#6B6B6B' }}>
              Click a country on the map
              <br />to view details
            </p>
          </div>
        )}
      </div>

      {/* ── Jurisdiction list (summary row) ────────────────────────────────── */}
      {detail && detail.jurisdictions.length > 0 && (
        <div className="mt-6 flex flex-wrap gap-2">
          {detail.jurisdictions.map((j) => {
            const iso3 = ISO2_TO_ISO3[j.jurisdictionCode] ?? j.jurisdictionCode;
            const isSelected = iso3 === selectedIso3;
            return (
              <button
                key={j.jurisdictionCode}
                onClick={() => setSelectedIso3(isSelected ? null : iso3)}
                className="flex items-center gap-1.5 rounded-full px-3 py-1 text-[12px] transition-all"
                style={{
                  background: isSelected ? 'rgba(255,120,25,0.14)' : 'rgba(255,255,255,0.04)',
                  border: isSelected
                    ? '1px solid rgba(255,120,25,0.35)'
                    : '1px solid rgba(255,255,255,0.08)',
                  color: isSelected ? '#FF9F55' : 'rgba(255,255,255,0.7)',
                  cursor: 'pointer',
                }}
              >
                <span>{jurisdictionFlag(j.jurisdictionCode)}</span>
                <span className="font-mono text-[11px]">{j.jurisdictionCode}</span>
                <VerdictPill verdict={j.verdict} showEmoji={false} />
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
    <div className="flex items-center justify-between gap-2">
      <span className="text-[11px] font-mono" style={{ color: '#6B6B6B' }}>{label}</span>
      <span className="text-[12px] font-mono text-white">{value}</span>
    </div>
  );
}
