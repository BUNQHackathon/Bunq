import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { listLaunches, getLaunch, jurisdictionFlag, type Launch, type JurisdictionRun } from '../api/launch';
import type { Verdict } from '../api/launch';
import KindBadge from '../components/KindBadge';
import VerdictPill from '../components/VerdictPill';

const VERDICT_EMOJI: Record<Verdict, string> = {
  GREEN: '🟢',
  AMBER: '🟡',
  RED: '🔴',
  PENDING: '⏳',
};

const VERDICT_RANK: Record<Verdict, number> = { GREEN: 0, AMBER: 1, RED: 2, PENDING: -1 };
function worstVerdict(rs: { verdict: Verdict }[]): Verdict | null {
  const valid = rs.filter(r => VERDICT_RANK[r.verdict] >= 0);
  if (valid.length === 0) return rs.length > 0 ? 'PENDING' : null;
  return valid.reduce((w, r) => VERDICT_RANK[r.verdict] > VERDICT_RANK[w.verdict] ? r : w, valid[0]).verdict;
}

interface LaunchRow {
  launch: Launch;
  jurisdictions: JurisdictionRun[];
}

export default function LaunchesPage() {
  const [rows, setRows] = useState<LaunchRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);

    listLaunches()
      .then(async (launches) => {
        if (cancelled) return;
        const details = await Promise.all(
          launches.map((l) =>
            getLaunch(l.id)
              .then((d) => ({ launch: d.launch, jurisdictions: d.jurisdictions }))
              .catch(() => ({ launch: l, jurisdictions: [] as JurisdictionRun[] })),
          ),
        );
        if (!cancelled) {
          setRows(details);
          setLoading(false);
        }
      })
      .catch((err) => {
        if (!cancelled) {
          setError(err instanceof Error ? err.message : 'Failed to load launches');
          setLoading(false);
        }
      });

    return () => { cancelled = true; };
  }, []);

  return (
    <div className="min-h-screen px-6 py-10 max-w-6xl mx-auto" style={{ color: '#E8E8E8' }}>
      <div className="flex items-center justify-between mb-8">
        <div>
          <h1 className="text-2xl font-semibold text-white mb-1">Launches</h1>
          <p className="font-mono text-[11px] uppercase tracking-wider" style={{ color: '#6B6B6B' }}>
            Product launches across markets
          </p>
        </div>
        <Link
          to="/launches/new"
          className="px-4 py-2 rounded-xl text-[13px] font-medium transition-all"
          style={{
            background: 'rgba(255,120,25,0.14)',
            border: '1px solid rgba(255,120,25,0.35)',
            color: '#FF9F55',
          }}
        >
          + New Launch
        </Link>
      </div>

      {loading && (
        <div
          className="rounded-xl px-6 py-12 text-center"
          style={{ background: '#0D0D0D', border: '1px solid rgba(255,255,255,0.06)' }}
        >
          <p className="text-[13px] font-mono animate-pulse" style={{ color: '#6B6B6B' }}>
            Loading…
          </p>
        </div>
      )}

      {error && (
        <div
          className="rounded-xl px-6 py-6"
          style={{ background: 'rgba(224,80,80,0.08)', border: '1px solid rgba(224,80,80,0.25)' }}
        >
          <p className="text-[13px]" style={{ color: '#E05050' }}>{error}</p>
        </div>
      )}

      {!loading && !error && rows.length === 0 && (
        <div
          className="rounded-xl px-6 py-12 text-center"
          style={{ background: '#0D0D0D', border: '1px solid rgba(255,255,255,0.06)' }}
        >
          <p className="text-[13px] mb-4" style={{ color: '#6B6B6B' }}>
            No launches yet.
          </p>
          <Link to="/launches/new" className="text-[13px] font-medium" style={{ color: '#FF9F55' }}>
            Create your first launch →
          </Link>
        </div>
      )}

      {!loading && !error && rows.length > 0 && (
        <div className="flex flex-col gap-3">
          {rows.map(({ launch, jurisdictions }) => (
            <Link
              key={launch.id}
              to={`/launches/${launch.id}`}
              className="block rounded-xl px-5 py-4 transition-all"
              style={{
                background: '#0D0D0D',
                border: '1px solid rgba(255,255,255,0.06)',
                textDecoration: 'none',
              }}
              onMouseEnter={(e) => {
                (e.currentTarget as HTMLAnchorElement).style.borderColor = 'rgba(255,120,25,0.2)';
                (e.currentTarget as HTMLAnchorElement).style.background = '#111';
              }}
              onMouseLeave={(e) => {
                (e.currentTarget as HTMLAnchorElement).style.borderColor = 'rgba(255,255,255,0.06)';
                (e.currentTarget as HTMLAnchorElement).style.background = '#0D0D0D';
              }}
            >
              {(() => {
                const agg: Verdict | null = launch.aggregateVerdict ?? worstVerdict(jurisdictions);
                return (
                  <div className="flex items-start justify-between gap-4 mb-2">
                    <div className="flex-1 min-w-0">
                      <div className="flex items-center gap-3 flex-wrap">
                        <span className="text-[15px] font-semibold text-white">{launch.name}</span>
                        {launch.kind && <KindBadge kind={launch.kind} />}
                        {launch.license && (
                          <span
                            className="text-[10px] font-mono rounded-full px-2.5 py-0.5"
                            style={{
                              background: 'rgba(110,183,232,0.12)',
                              color: '#6EB7E8',
                              border: '1px solid rgba(110,183,232,0.25)',
                            }}
                          >
                            {launch.license}
                          </span>
                        )}
                        {agg && <VerdictPill verdict={agg} />}
                        {jurisdictions.length > 0 && (
                          <span className="text-[11px] font-mono" style={{ color: '#6B6B6B' }}>
                            {jurisdictions.length} markets
                          </span>
                        )}
                      </div>
                      {launch.brief && (
                        <p className="text-[13px] mt-1 line-clamp-1" style={{ color: 'rgba(255,255,255,0.5)' }}>
                          {launch.brief}
                        </p>
                      )}
                    </div>
                    <span className="text-[11px] font-mono shrink-0" style={{ color: '#6B6B6B' }}>
                      {new Date(launch.createdAt).toLocaleDateString()}
                    </span>
                  </div>
                );
              })()}

              {jurisdictions.length > 0 && (
                <div className="flex flex-wrap gap-2 mt-3">
                  {jurisdictions.map((j) => (
                    <span
                      key={j.jurisdictionCode}
                      className="flex items-center gap-1 rounded-full px-2.5 py-1 text-[12px]"
                      style={{
                        background: '#1A1A1A',
                        border: '1px solid rgba(255,255,255,0.08)',
                        color: 'rgba(255,255,255,0.75)',
                      }}
                    >
                      {jurisdictionFlag(j.jurisdictionCode)}
                      <span className="font-mono text-[11px]">{j.jurisdictionCode}</span>
                      <span>{VERDICT_EMOJI[j.verdict]}</span>
                    </span>
                  ))}
                </div>
              )}
            </Link>
          ))}
        </div>
      )}
    </div>
  );
}
