import { useEffect, useState } from 'react';
import { useParams, useNavigate, Link } from 'react-router-dom';
import { getSession, type Session } from '../api/session';

function formatDate(iso: string): string {
  try {
    const d = new Date(iso);
    if (isNaN(d.getTime())) return '—';
    return d.toLocaleString('en-US', { month: 'short', day: 'numeric', year: 'numeric', hour: '2-digit', minute: '2-digit' });
  } catch {
    return '—';
  }
}

const STATE_COLOR: Record<string, string> = {
  COMPLETE: 'text-emerald-400 bg-emerald-400/10',
  FAILED: 'text-red-400 bg-red-400/10',
  CREATED: 'text-white/50 bg-white/[0.06]',
};

function StateBadge({ state }: { state: string }) {
  const cls = STATE_COLOR[state] ?? 'text-yellow-400 bg-yellow-400/10';
  return (
    <span className={`inline-block rounded-full px-2.5 py-0.5 text-[11px] font-mono ${cls}`}>
      {state}
    </span>
  );
}

function Row({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex gap-4 py-2.5" style={{ borderBottom: '1px solid rgba(255,255,255,0.05)' }}>
      <dt className="w-40 shrink-0 font-mono text-[11px] uppercase tracking-wide text-white/30 pt-0.5">{label}</dt>
      <dd className="flex-1 text-[13px] text-white/80">{children}</dd>
    </div>
  );
}

export default function SessionDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const [session, setSession] = useState<Session | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!id) return;
    let cancelled = false;
    setLoading(true);
    setError(null);
    getSession(id)
      .then((data) => { if (!cancelled) setSession(data); })
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

  if (error || !session) {
    return (
      <div className="flex h-[calc(100vh-56px)] flex-col items-center justify-center gap-4 bg-[#0D0D0D]">
        <p className="text-[13px] text-white/40">{error ?? 'Session not found'}</p>
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
        <span className="font-mono text-[11px] uppercase tracking-wide text-white/20">Session</span>
      </div>

      <div className="mb-6 flex items-center gap-3">
        <h1 className="text-[22px] font-semibold text-white/90 truncate">{session.id}</h1>
        <StateBadge state={session.state} />
      </div>

      <dl>
        {session.regulation && <Row label="Regulation">{session.regulation}</Row>}
        {session.policy && <Row label="Policy">{session.policy}</Row>}
        {session.verdict && <Row label="Verdict">{session.verdict}</Row>}
        {session.counterparties && session.counterparties.length > 0 && (
          <Row label="Counterparties">{session.counterparties.join(', ')}</Row>
        )}
        {session.documentIds && session.documentIds.length > 0 && (
          <Row label="Documents">
            <ul className="space-y-1">
              {session.documentIds.map((docId) => (
                <li key={docId}>
                  <Link to={`/doc/${docId}`} className="text-[#FF7819] hover:underline font-mono text-[12px]">
                    {docId}
                  </Link>
                </li>
              ))}
            </ul>
          </Row>
        )}
        {session.errorMessage && <Row label="Error">{session.errorMessage}</Row>}
        <Row label="Created">{formatDate(session.createdAt)}</Row>
        <Row label="Updated">{formatDate(session.updatedAt)}</Row>
      </dl>
    </div>
  );
}
