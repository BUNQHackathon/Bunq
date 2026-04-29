import { useEffect, useState } from 'react';
import { useParams, Link } from 'react-router-dom';
import { listControls, type Control } from '../api/session';

const IMPL_STATUS_COLOR: Record<string, string> = {
  implemented: 'text-emerald-400 bg-emerald-400/10',
  in_progress: 'text-yellow-400 bg-yellow-400/10',
  planned: 'text-blue-400 bg-blue-400/10',
  unclear: 'text-white/40 bg-white/[0.05]',
};

function ImplStatusBadge({ value }: { value: string }) {
  const cls = IMPL_STATUS_COLOR[value] ?? 'text-white/50 bg-white/[0.06]';
  return (
    <span className={`inline-block rounded-full px-2.5 py-0.5 text-[11px] font-mono ${cls}`}>
      {value}
    </span>
  );
}

function shortId(id: string): string {
  return id.length > 8 ? id.slice(-8) : id;
}

export default function ControlsPage() {
  const { id: sessionId } = useParams<{ id: string }>();
  const [controls, setControls] = useState<Control[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!sessionId) return;
    let cancelled = false;
    setLoading(true);
    setError(null);
    listControls(sessionId)
      .then((data) => {
        if (!cancelled) {
          const sorted = [...data].sort((a, b) =>
            a.controlType.localeCompare(b.controlType) ||
            a.category.localeCompare(b.category)
          );
          setControls(sorted);
        }
      })
      .catch((err) => { if (!cancelled) setError(err instanceof Error ? err.message : 'Failed to load controls'); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [sessionId]);

  if (loading) {
    return (
      <div className="flex h-[calc(100vh-56px)] items-center justify-center bg-[#0D0D0D]">
        <span className="font-mono text-[12px] text-white/30">Loading…</span>
      </div>
    );
  }

  if (error) {
    return (
      <div className="flex h-[calc(100vh-56px)] flex-col items-center justify-center gap-4 bg-[#0D0D0D]">
        <p className="text-[13px] text-white/40">{error}</p>
        <Link
          to={`/session/${sessionId}`}
          className="rounded-full bg-white/[0.06] px-4 py-1.5 text-[12px] text-white/60 hover:text-white transition-colors"
        >
          ← Session
        </Link>
      </div>
    );
  }

  return (
    <div className="min-h-[calc(100vh-56px)] bg-[#0D0D0D] px-8 py-8">
      {/* Header */}
      <div className="mb-6 flex items-center gap-3">
        <Link
          to={`/session/${sessionId}`}
          className="rounded-full bg-white/[0.05] px-3 py-1.5 text-[11px] text-white/60 hover:text-white transition-colors"
        >
          ← Session
        </Link>
        <span className="font-mono text-[11px] uppercase tracking-wide text-white/20">Controls</span>
      </div>

      <div className="mb-6 flex items-center gap-3">
        <h1 className="text-[22px] font-semibold text-white/90">Controls</h1>
        <span className="rounded-full bg-white/[0.06] px-2.5 py-0.5 text-[12px] font-mono text-white/50">
          {controls.length} controls
        </span>
      </div>

      {controls.length === 0 ? (
        <div className="flex flex-col items-center justify-center py-24 gap-3">
          <p className="text-[13px] text-white/30">No controls extracted yet.</p>
        </div>
      ) : (
        <div className="overflow-x-auto rounded-xl" style={{ border: '1px solid rgba(255,255,255,0.06)' }}>
          <table className="w-full text-[13px]">
            <thead>
              <tr style={{ borderBottom: '1px solid rgba(255,255,255,0.06)' }}>
                {['ID', 'Type', 'Category', 'Owner', 'Implementation Status', 'Standards'].map((col) => (
                  <th
                    key={col}
                    className="px-4 py-3 text-left font-mono text-[10px] uppercase tracking-wide text-white/30"
                  >
                    {col}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {controls.map((ctrl, i) => (
                <tr
                  key={ctrl.id}
                  style={{
                    borderBottom: i < controls.length - 1 ? '1px solid rgba(255,255,255,0.04)' : undefined,
                  }}
                  className="hover:bg-white/[0.02] transition-colors"
                >
                  <td className="px-4 py-3">
                    <Link
                      to={`/control/${ctrl.id}`}
                      title={ctrl.id}
                      className="font-mono text-[11px] text-[#FF7819] hover:underline"
                    >
                      {shortId(ctrl.id)}
                    </Link>
                  </td>
                  <td className="px-4 py-3 text-white/70">{ctrl.controlType}</td>
                  <td className="px-4 py-3 text-white/70">{ctrl.category}</td>
                  <td className="px-4 py-3 text-white/50">{ctrl.owner ?? '—'}</td>
                  <td className="px-4 py-3">
                    <ImplStatusBadge value={ctrl.implementationStatus} />
                  </td>
                  <td className="px-4 py-3">
                    {ctrl.mappedStandards && ctrl.mappedStandards.length > 0 ? (
                      <div className="flex flex-wrap gap-1">
                        {ctrl.mappedStandards.map((s) => (
                          <span
                            key={s}
                            className="rounded-full bg-white/[0.06] px-2 py-0.5 text-[10px] font-mono text-white/50"
                          >
                            {s}
                          </span>
                        ))}
                      </div>
                    ) : (
                      <span className="text-white/25">—</span>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
