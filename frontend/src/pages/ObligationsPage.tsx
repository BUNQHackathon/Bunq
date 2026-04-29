import { useEffect, useState } from 'react';
import { useParams, Link } from 'react-router-dom';
import { listObligations, type Obligation } from '../api/session';

const DEONTIC_COLOR: Record<string, string> = {
  O: 'text-amber-400 bg-amber-400/10',
  F: 'text-red-400 bg-red-400/10',
  P: 'text-emerald-400 bg-emerald-400/10',
};

function DeonticBadge({ value }: { value: string }) {
  const cls = DEONTIC_COLOR[value] ?? 'text-white/50 bg-white/[0.06]';
  return (
    <span className={`inline-block rounded-full px-2.5 py-0.5 text-[11px] font-mono ${cls}`}>
      {value}
    </span>
  );
}

function shortId(id: string): string {
  return id.length > 8 ? id.slice(-8) : id;
}

function sourceLabel(o: Obligation): { label: string; full?: string } {
  const parts: string[] = [];
  if (o.source.article) parts.push(`Art. ${o.source.article}`);
  if (o.source.section) parts.push(`§${o.source.section}`);
  if (o.source.paragraph != null) parts.push(`¶${o.source.paragraph}`);
  if (parts.length > 0) return { label: parts.join(' ') };
  const txt = o.source.sourceText;
  if (txt) {
    const truncated = txt.length > 60 ? txt.slice(0, 60) + '…' : txt;
    return { label: truncated, full: txt };
  }
  return { label: '—' };
}

export default function ObligationsPage() {
  const { id: sessionId } = useParams<{ id: string }>();
  const [obligations, setObligations] = useState<Obligation[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!sessionId) return;
    let cancelled = false;
    setLoading(true);
    setError(null);
    listObligations(sessionId)
      .then((data) => {
        if (!cancelled) {
          const sorted = [...data].sort((a, b) =>
            (a.riskCategory ?? '').localeCompare(b.riskCategory ?? '') ||
            (a.subject ?? '').localeCompare(b.subject ?? '')
          );
          setObligations(sorted);
        }
      })
      .catch((err) => { if (!cancelled) setError(err instanceof Error ? err.message : 'Failed to load obligations'); })
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
        <span className="font-mono text-[11px] uppercase tracking-wide text-white/20">Obligations</span>
      </div>

      <div className="mb-6 flex items-center gap-3">
        <h1 className="text-[22px] font-semibold text-white/90">Obligations</h1>
        <span className="rounded-full bg-white/[0.06] px-2.5 py-0.5 text-[12px] font-mono text-white/50">
          {obligations.length} obligations
        </span>
      </div>

      {obligations.length === 0 ? (
        <div className="flex flex-col items-center justify-center py-24 gap-3">
          <p className="text-[13px] text-white/30">No obligations extracted yet.</p>
        </div>
      ) : (
        <div className="overflow-x-auto rounded-xl" style={{ border: '1px solid rgba(255,255,255,0.06)' }}>
          <table className="w-full text-[13px]">
            <thead>
              <tr style={{ borderBottom: '1px solid rgba(255,255,255,0.06)' }}>
                {['ID', 'Deontic', 'Subject', 'Action', 'Risk Category', 'Source', 'Confidence'].map((col) => (
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
              {obligations.map((obl, i) => {
                const src = sourceLabel(obl);
                return (
                  <tr
                    key={obl.id}
                    style={{
                      borderBottom: i < obligations.length - 1 ? '1px solid rgba(255,255,255,0.04)' : undefined,
                    }}
                    className="hover:bg-white/[0.02] transition-colors"
                  >
                    <td className="px-4 py-3">
                      <Link
                        to={`/obligation/${obl.id}`}
                        title={obl.id}
                        className="font-mono text-[11px] text-[#FF7819] hover:underline"
                      >
                        {shortId(obl.id)}
                      </Link>
                    </td>
                    <td className="px-4 py-3">
                      <DeonticBadge value={obl.deontic} />
                    </td>
                    <td className="px-4 py-3 text-white/70">{obl.subject ?? '—'}</td>
                    <td className="px-4 py-3 text-white/70">{obl.action ?? '—'}</td>
                    <td className="px-4 py-3">
                      {obl.riskCategory ? (
                        <span className="rounded-full bg-white/[0.06] px-2 py-0.5 text-[10px] font-mono text-white/50">
                          {obl.riskCategory}
                        </span>
                      ) : (
                        <span className="text-white/25">—</span>
                      )}
                    </td>
                    <td className="px-4 py-3 text-white/60" title={src.full}>
                      <span className="font-mono text-[11px]">{src.label}</span>
                    </td>
                    <td className="px-4 py-3 text-white/50 font-mono text-[11px]">
                      {obl.extractionConfidence != null
                        ? `${Math.round(obl.extractionConfidence * 100)}%`
                        : '—'}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
