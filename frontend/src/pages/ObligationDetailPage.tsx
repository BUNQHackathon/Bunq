import { useEffect, useState } from 'react';
import { useParams, useNavigate, Link } from 'react-router-dom';
import { getObligation, type Obligation } from '../api/session';

function Row({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex gap-4 py-2.5" style={{ borderBottom: '1px solid rgba(255,255,255,0.05)' }}>
      <dt className="w-40 shrink-0 font-mono text-[11px] uppercase tracking-wide text-white/30 pt-0.5">{label}</dt>
      <dd className="flex-1 text-[13px] text-white/80">{children}</dd>
    </div>
  );
}

const SEVERITY_COLOR: Record<string, string> = {
  critical: 'text-red-400 bg-red-400/10',
  high: 'text-orange-400 bg-orange-400/10',
  medium: 'text-yellow-400 bg-yellow-400/10',
  low: 'text-emerald-400 bg-emerald-400/10',
};

function SeverityBadge({ severity }: { severity: string }) {
  const cls = SEVERITY_COLOR[severity] ?? 'text-white/50 bg-white/[0.06]';
  return (
    <span className={`inline-block rounded-full px-2.5 py-0.5 text-[11px] font-mono ${cls}`}>
      {severity}
    </span>
  );
}

export default function ObligationDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const [obligation, setObligation] = useState<Obligation | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!id) return;
    let cancelled = false;
    setLoading(true);
    setError(null);
    getObligation(id)
      .then((data) => { if (!cancelled) setObligation(data); })
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

  if (error || !obligation) {
    return (
      <div className="flex h-[calc(100vh-56px)] flex-col items-center justify-center gap-4 bg-[#0D0D0D]">
        <p className="text-[13px] text-white/40">{error ?? 'Obligation not found'}</p>
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

  const title = [obligation.deontic, obligation.subject].filter(Boolean).join(' ') || obligation.id;

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
        <span className="font-mono text-[11px] uppercase tracking-wide text-white/20">Obligation</span>
      </div>

      <div className="mb-6 flex items-start gap-3">
        <h1 className="text-[22px] font-semibold text-white/90 flex-1">{title}</h1>
        <SeverityBadge severity={obligation.severity} />
      </div>

      <dl>
        {obligation.action && <Row label="Action">{obligation.action}</Row>}
        <Row label="Type">{obligation.obligationType}</Row>
        {obligation.riskCategory && <Row label="Risk Category">{obligation.riskCategory}</Row>}
        {obligation.conditions && obligation.conditions.length > 0 && (
          <Row label="Conditions">
            <ul className="space-y-1 list-disc list-inside">
              {obligation.conditions.map((c, i) => <li key={i}>{c}</li>)}
            </ul>
          </Row>
        )}
        {obligation.applicableJurisdictions && obligation.applicableJurisdictions.length > 0 && (
          <Row label="Jurisdictions">{obligation.applicableJurisdictions.join(', ')}</Row>
        )}
        {obligation.applicableEntities && obligation.applicableEntities.length > 0 && (
          <Row label="Entities">{obligation.applicableEntities.join(', ')}</Row>
        )}
        {obligation.regulatoryPenaltyRange && (
          <Row label="Penalty Range">{obligation.regulatoryPenaltyRange}</Row>
        )}
        {obligation.extractionConfidence !== undefined && (
          <Row label="Confidence">{(obligation.extractionConfidence * 100).toFixed(0)}%</Row>
        )}
        <Row label="Session">
          <Link to={`/session/${obligation.sessionId}`} className="text-[#FF7819] hover:underline font-mono text-[12px]">
            {obligation.sessionId}
          </Link>
        </Row>
        {obligation.regulationId && (
          <Row label="Regulation">
            <Link to={`/doc/${obligation.regulationId}`} className="text-[#FF7819] hover:underline font-mono text-[12px]">
              {obligation.regulationId}
            </Link>
          </Row>
        )}
      </dl>
    </div>
  );
}
