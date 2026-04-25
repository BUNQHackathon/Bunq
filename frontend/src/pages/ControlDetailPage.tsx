import { useEffect, useState } from 'react';
import { useParams, useNavigate, Link } from 'react-router-dom';
import { getControl, type Control } from '../api/session';

function Row({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex gap-4 py-2.5" style={{ borderBottom: '1px solid rgba(255,255,255,0.05)' }}>
      <dt className="w-40 shrink-0 font-mono text-[11px] uppercase tracking-wide text-white/30 pt-0.5">{label}</dt>
      <dd className="flex-1 text-[13px] text-white/80">{children}</dd>
    </div>
  );
}

const STATUS_COLOR: Record<string, string> = {
  passed: 'text-emerald-400 bg-emerald-400/10',
  failed: 'text-red-400 bg-red-400/10',
  pending: 'text-yellow-400 bg-yellow-400/10',
  unknown: 'text-white/40 bg-white/[0.05]',
  implemented: 'text-emerald-400 bg-emerald-400/10',
  in_progress: 'text-yellow-400 bg-yellow-400/10',
  planned: 'text-blue-400 bg-blue-400/10',
  unclear: 'text-white/40 bg-white/[0.05]',
};

function StatusBadge({ value }: { value: string }) {
  const cls = STATUS_COLOR[value] ?? 'text-white/50 bg-white/[0.06]';
  return (
    <span className={`inline-block rounded-full px-2.5 py-0.5 text-[11px] font-mono ${cls}`}>
      {value}
    </span>
  );
}

export default function ControlDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const [control, setControl] = useState<Control | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!id) return;
    let cancelled = false;
    setLoading(true);
    setError(null);
    getControl(id)
      .then((data) => { if (!cancelled) setControl(data); })
      .catch((err) => { if (!cancelled) setError(err instanceof Error ? err.message : 'Not found'); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [id]);

  if (loading) {
    return (
      <div className="flex h-[calc(100vh-56px)] items-center justify-center bg-[#0D0D0D]">
        <span className="font-mono text-[12px] text-white/30">Loading…</span>
      </div>
    );
  }

  if (error || !control) {
    return (
      <div className="flex h-[calc(100vh-56px)] flex-col items-center justify-center gap-4 bg-[#0D0D0D]">
        <p className="text-[13px] text-white/40">{error ?? 'Control not found'}</p>
        <button
          type="button"
          onClick={() => navigate(-1)}
          className="rounded-full bg-white/[0.06] px-4 py-1.5 text-[12px] text-white/60 hover:text-white transition-colors"
        >
          ← Back
        </button>
      </div>
    );
  }

  const title = control.description ?? control.id;
  const titleTruncated = title.length > 80 ? title.slice(0, 80) + '…' : title;

  return (
    <div className="min-h-[calc(100vh-56px)] bg-[#0D0D0D] px-8 py-8 max-w-3xl mx-auto">
      <div className="mb-6 flex items-center gap-3">
        <button
          type="button"
          onClick={() => navigate(-1)}
          className="rounded-full bg-white/[0.05] px-3 py-1.5 text-[11px] text-white/60 hover:text-white transition-colors"
        >
          ← Back
        </button>
        <span className="font-mono text-[11px] uppercase tracking-wide text-white/20">Control</span>
      </div>

      <div className="mb-2 flex items-start gap-3">
        <h1 className="text-[22px] font-semibold text-white/90 flex-1">{titleTruncated}</h1>
        <StatusBadge value={control.implementationStatus} />
      </div>
      {title !== titleTruncated && (
        <p className="mb-5 text-[13px] text-white/50 leading-relaxed">{title}</p>
      )}

      <dl className="mt-4">
        <Row label="Type">{control.controlType}</Row>
        <Row label="Category">{control.category}</Row>
        {control.owner && <Row label="Owner">{control.owner}</Row>}
        {control.testingCadence && <Row label="Testing Cadence">{control.testingCadence}</Row>}
        {control.evidenceType && <Row label="Evidence Type">{control.evidenceType}</Row>}
        <Row label="Testing Status"><StatusBadge value={control.testingStatus} /></Row>
        {control.lastTested && <Row label="Last Tested">{control.lastTested}</Row>}
        {control.mappedStandards && control.mappedStandards.length > 0 && (
          <Row label="Standards">
            <div className="flex flex-wrap gap-1.5">
              {control.mappedStandards.map((s) => (
                <span key={s} className="rounded-full bg-white/[0.06] px-2 py-0.5 text-[11px] font-mono text-white/60">{s}</span>
              ))}
            </div>
          </Row>
        )}
        {control.linkedTools && control.linkedTools.length > 0 && (
          <Row label="Linked Tools">
            <div className="flex flex-wrap gap-1.5">
              {control.linkedTools.map((t) => (
                <span key={t} className="rounded-full bg-white/[0.06] px-2 py-0.5 text-[11px] font-mono text-white/60">{t}</span>
              ))}
            </div>
          </Row>
        )}
        <Row label="Session">
          <Link to={`/session/${control.sessionId}`} className="text-[#FF7819] hover:underline font-mono text-[12px]">
            {control.sessionId}
          </Link>
        </Row>
      </dl>
    </div>
  );
}
