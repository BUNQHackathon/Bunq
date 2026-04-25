import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { listLaunches, deleteLaunch, type Launch, type LaunchKind, type LaunchJurisdictionSummary } from '../api/launch';
import type { Verdict } from '../api/launch';
import VerdictPill from '../components/VerdictPill';
import { IconPlus, IconClose } from '../components/icons';

const VERDICT_RANK: Record<Verdict, number> = { GREEN: 0, AMBER: 1, RED: 2, PENDING: -1 };

function worstVerdict(rs: { verdict: Verdict }[]): Verdict | null {
  const valid = rs.filter(r => VERDICT_RANK[r.verdict] >= 0);
  if (valid.length === 0) return rs.length > 0 ? 'PENDING' : null;
  return valid.reduce((w, r) => VERDICT_RANK[r.verdict] > VERDICT_RANK[w.verdict] ? r : w, valid[0]).verdict;
}

interface LaunchRow {
  launch: Launch;
  jurisdictions: LaunchJurisdictionSummary[];
}

function kindDotClass(kind: LaunchKind | undefined): string {
  if (!kind) return 'tc';
  switch (kind) {
    case 'PRODUCT': return 'lic';
    case 'POLICY':  return 'priv';
    case 'PROCESS': return 'tc';
    default:        return 'tc';
  }
}

const placeholderKeys = [0, 1, 2] as const;

export default function LaunchesPage() {
  const [rows, setRows] = useState<LaunchRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [deletingId, setDeletingId] = useState<string | null>(null);

  async function handleDelete(e: React.MouseEvent, launch: Launch) {
    e.preventDefault();
    e.stopPropagation();
    if (!window.confirm(`Delete launch '${launch.name}'? This cannot be undone.`)) return;
    setDeletingId(launch.id);
    try {
      await deleteLaunch(launch.id);
      setRows((prev) => prev.filter((r) => r.launch.id !== launch.id));
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to delete launch');
    } finally {
      setDeletingId(null);
    }
  }

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);

    listLaunches()
      .then((launches) => {
        if (cancelled) return;
        setRows(launches.map((l) => ({ launch: l, jurisdictions: l.jurisdictions ?? [] })));
        setLoading(false);
      })
      .catch((err) => {
        if (!cancelled) {
          setError(err instanceof Error ? err.message : 'Failed to load launches');
          setLoading(false);
        }
      });

    return () => { cancelled = true; };
  }, []);

  const GRID_LINE_COLOR = 'rgba(214, 214, 214, 0.13)';
  const gridOverlayStyle = {
    position: 'absolute' as const,
    inset: 0,
    pointerEvents: 'none' as const,
    zIndex: 0,
    backgroundImage:
      `linear-gradient(${GRID_LINE_COLOR} 1px, transparent 1px),` +
      `linear-gradient(90deg, ${GRID_LINE_COLOR} 1px, transparent 1px)`,
    backgroundSize: '44px 44px',
    WebkitMaskImage: 'radial-gradient(ellipse at center, black 40%, transparent 95%)',
    maskImage: 'radial-gradient(ellipse at center, black 40%, transparent 95%)',
  };

  return (
    <div className="folders" style={{ display: 'block', position: 'relative', minHeight: '100%' }}>
      <div className="folders__main" style={{ padding: '40px 60px 80px', minHeight: '100%', boxSizing: 'border-box', position: 'relative' }}>
        <div aria-hidden style={gridOverlayStyle} />
        <div style={{ position: 'relative', zIndex: 1 }}>
        {/* Header */}
        <div style={{ display: 'flex', alignItems: 'baseline', justifyContent: 'space-between', marginBottom: 32 }}>
          <div>
            <div className="mono-label mono-label--ink" style={{ marginBottom: 10 }}>
              LAUNCHES · {loading ? '…' : rows.length} ACTIVE
            </div>
            <h1 className="serif-display" style={{ fontSize: 56, margin: 0 }}>
              Product launches<span style={{ color: 'var(--orange)' }}>.</span>
            </h1>
          </div>
          <Link to="/launches/new" className="btn btn--orange-hollow">
            <IconPlus size={14} /> New launch
          </Link>
        </div>

        {/* Error state */}
        {error && (
          <div
            style={{
              background: 'rgba(217,74,74,0.08)',
              border: '1px solid rgba(217,74,74,0.3)',
              color: 'var(--danger, #d94a4a)',
              borderRadius: 8,
              padding: '14px 20px',
              marginBottom: 24,
              fontSize: 13,
              fontFamily: 'var(--mono)',
            }}
          >
            {error}
          </div>
        )}

        {/* Card grid */}
        <div className="folders__grid">
          {/* Loading placeholders */}
          {loading && placeholderKeys.map((k) => (
            <div key={k} className="doccard" style={{ opacity: 0.5, animation: 'pulse 1.5s ease-in-out infinite' }}>
              <div className="doccard__head">
                <span className="srcrow__dot srcrow__dot--tc" />
                <span className="mono-label">LOADING</span>
              </div>
              <div className="doccard__title" style={{ width: '60%', height: 18, background: 'var(--line-0)', borderRadius: 4 }} />
              <div className="doccard__meta" style={{ minHeight: 36 }} />
              <div className="doccard__foot" />
            </div>
          ))}

          {/* Empty state */}
          {!loading && !error && rows.length === 0 && (
            <div className="doccard" style={{ gridColumn: '1 / -1', textAlign: 'center', cursor: 'default' }}>
              <div className="doccard__title" style={{ color: 'var(--ink-2)', marginBottom: 16 }}>
                No launches yet.
              </div>
              <Link to="/launches/new" className="btn btn--orange-hollow btn--sm">
                <IconPlus size={12} /> Create your first launch
              </Link>
            </div>
          )}

          {/* Launch cards */}
          {!loading && !error && rows.map(({ launch, jurisdictions }) => {
            const agg: Verdict | null = launch.aggregateVerdict ?? worstVerdict(jurisdictions);
            return (
              <Link
                key={launch.id}
                to={`/launches/${launch.id}`}
                className="doccard"
                style={{ textDecoration: 'none', position: 'relative', opacity: deletingId === launch.id ? 0.5 : 1 }}
              >
                <button
                  type="button"
                  onClick={(e) => handleDelete(e, launch)}
                  disabled={deletingId === launch.id}
                  aria-label={`Delete launch ${launch.name}`}
                  title="Delete launch"
                  style={{
                    position: 'absolute',
                    top: 8,
                    right: 8,
                    width: 24,
                    height: 24,
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    background: 'transparent',
                    border: 'none',
                    borderRadius: 4,
                    color: 'var(--ink-2)',
                    cursor: 'pointer',
                    zIndex: 2,
                  }}
                  onMouseEnter={(e) => { e.currentTarget.style.color = 'var(--danger, #d94a4a)'; e.currentTarget.style.background = 'rgba(217,74,74,0.08)'; }}
                  onMouseLeave={(e) => { e.currentTarget.style.color = 'var(--ink-2)'; e.currentTarget.style.background = 'transparent'; }}
                >
                  <IconClose size={12} />
                </button>
                <div className="doccard__head">
                  <span className={`srcrow__dot srcrow__dot--${kindDotClass(launch.kind)}`} />
                  <span className="mono-label">{launch.kind ?? 'PRODUCT'}</span>
                  {launch.license && (
                    <span className="mono-label" style={{ color: 'var(--ink-2)' }}>· {launch.license}</span>
                  )}
                </div>
                <div className="doccard__title">{launch.name}</div>
                <div className="doccard__meta" style={{ minHeight: 36 }}>
                  {launch.brief || '—'}
                </div>
                <div className="doccard__foot">
                  {agg && <VerdictPill verdict={agg} showEmoji={false} />}
                  <span className="mono-label" style={{ color: 'var(--ink-2)' }}>
                    {jurisdictions.length} market{jurisdictions.length !== 1 ? 's' : ''}
                  </span>
                </div>
              </Link>
            );
          })}
        </div>
        </div>
      </div>
    </div>
  );
}
