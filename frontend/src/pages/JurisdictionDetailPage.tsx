import { useState, useEffect } from 'react';
import { Link, useParams, useNavigate } from 'react-router-dom';
import { getJurisdictionLaunches, type JurisdictionLaunchRow } from '../api/jurisdictions';
import { jurisdictionFlag, jurisdictionLabel, downloadProofPack } from '../api/launch';
import type { Verdict, LaunchKind } from '../api/launch';
import VerdictPill from '../components/VerdictPill';
import KindBadge from '../components/KindBadge';

const VERDICT_RANK: Record<Verdict, number> = { GREEN: 0, AMBER: 1, RED: 2, PENDING: -1 };

function worst(rs: { verdict: Verdict }[]): Verdict {
  const valid = rs.filter(r => VERDICT_RANK[r.verdict] >= 0);
  if (!valid.length) return 'PENDING';
  return valid.reduce((w, r) => VERDICT_RANK[r.verdict] > VERDICT_RANK[w] ? r.verdict : w, valid[0].verdict);
}

const ALL_KINDS: LaunchKind[] = ['PRODUCT', 'POLICY', 'PROCESS'];

export default function JurisdictionDetailPage() {
  const { code } = useParams<{ code: string }>();
  const navigate = useNavigate();
  const [data, setData] = useState<{ code: string; launches: JurisdictionLaunchRow[] } | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [hoveredRow, setHoveredRow] = useState<string | null>(null);

  useEffect(() => {
    if (!code) return;
    let cancelled = false;
    getJurisdictionLaunches(code)
      .then(d => !cancelled && setData(d))
      .catch(e => !cancelled && setError(e.message));
    return () => { cancelled = true; };
  }, [code]);

  const flag = jurisdictionFlag(code ?? '');
  const label = jurisdictionLabel(code ?? '');

  const cardStyle: React.CSSProperties = {
    background: '#0D0D0D',
    border: '1px solid rgba(255,255,255,0.06)',
    borderRadius: '12px',
  };

  return (
    <div className="min-h-screen px-6 py-10 max-w-6xl mx-auto" style={{ color: '#E8E8E8' }}>
      <div className="mb-8">
        <Link
          to="/jurisdictions"
          className="text-[13px] font-medium mb-6 inline-block"
          style={{ color: '#FF9F55', textDecoration: 'none' }}
        >
          ← All jurisdictions
        </Link>

        <div className="flex items-center justify-between mt-4 flex-wrap gap-3">
          <div>
            <h1 className="text-2xl font-semibold text-white mb-1">
              {flag} {label}
            </h1>
            <p className="font-mono text-[11px] uppercase tracking-wider" style={{ color: '#6B6B6B' }}>
              {code} · {data ? `${data.launches.length} launch${data.launches.length !== 1 ? 'es' : ''} running` : '—'}
            </p>
          </div>
          {data && data.launches.length > 0 && (
            <VerdictPill verdict={worst(data.launches)} />
          )}
        </div>
      </div>

      {data === null && !error && (
        <div className="rounded-xl px-6 py-12 text-center" style={cardStyle}>
          <p className="font-mono text-[13px]" style={{ color: '#6B6B6B' }}>Loading…</p>
        </div>
      )}

      {error && (
        <div className="rounded-xl px-6 py-4 mb-6" style={{ background: 'rgba(239,68,68,0.1)', border: '1px solid rgba(239,68,68,0.3)', borderRadius: '12px' }}>
          <p className="text-[13px] font-mono" style={{ color: '#ef4444' }}>{error}</p>
        </div>
      )}

      {data && (
        <>
          <div className="mb-6 p-5" style={cardStyle}>
            <p className="font-mono text-[10px] uppercase tracking-wider mb-3" style={{ color: '#6B6B6B' }}>
              Risk by kind
            </p>
            <div className="flex flex-wrap gap-3">
              {ALL_KINDS.map(kind => {
                const rows = data.launches.filter(l => l.kind === kind);
                return (
                  <div
                    key={kind}
                    className="flex items-center gap-2 rounded-lg px-3 py-2"
                    style={{
                      background: rows.length > 0 ? 'rgba(255,255,255,0.04)' : 'rgba(255,255,255,0.02)',
                      border: '1px solid rgba(255,255,255,0.06)',
                    }}
                  >
                    <KindBadge kind={kind} />
                    {rows.length > 0 ? (
                      <VerdictPill verdict={worst(rows)} />
                    ) : (
                      <span className="font-mono text-[11px]" style={{ color: '#6B6B6B' }}>—</span>
                    )}
                  </div>
                );
              })}
            </div>
          </div>

          <div style={cardStyle}>
            <div className="px-5 py-4 border-b" style={{ borderColor: 'rgba(255,255,255,0.06)' }}>
              <p className="font-mono text-[10px] uppercase tracking-wider" style={{ color: '#6B6B6B' }}>
                Launches in this jurisdiction
              </p>
            </div>

            {data.launches.length === 0 ? (
              <div className="px-6 py-12 text-center">
                <p className="text-[13px]" style={{ color: '#6B6B6B' }}>No launches running in this country yet.</p>
              </div>
            ) : (
              <div style={{ overflowX: 'auto' }}>
                <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                  <thead>
                    <tr>
                      {['Kind', 'Name', 'Verdict', 'Gaps', 'Sanctions', 'Last Run', 'Actions'].map(h => (
                        <th
                          key={h}
                          style={{
                            padding: '10px 16px',
                            textAlign: 'left',
                            fontFamily: 'monospace',
                            fontSize: '10px',
                            textTransform: 'uppercase',
                            letterSpacing: '0.08em',
                            color: '#6B6B6B',
                            fontWeight: 500,
                            borderBottom: '1px solid rgba(255,255,255,0.06)',
                            whiteSpace: 'nowrap',
                          }}
                        >
                          {h}
                        </th>
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                    {data.launches.map(row => (
                      <tr
                        key={row.launchId}
                        onMouseEnter={() => setHoveredRow(row.launchId)}
                        onMouseLeave={() => setHoveredRow(null)}
                        style={{
                          background: hoveredRow === row.launchId ? 'rgba(255,159,85,0.04)' : 'transparent',
                          borderBottom: '1px solid rgba(255,255,255,0.04)',
                          transition: 'background 0.15s',
                        }}
                      >
                        <td style={{ padding: '10px 16px' }}>
                          <KindBadge kind={row.kind} />
                        </td>
                        <td style={{ padding: '10px 16px' }}>
                          <Link
                            to={`/launches/${row.launchId}`}
                            style={{
                              color: '#E8E8E8',
                              textDecoration: 'none',
                              fontSize: '13px',
                            }}
                            onMouseEnter={e => (e.currentTarget.style.textDecoration = 'underline', e.currentTarget.style.textDecorationColor = '#FF9F55', e.currentTarget.style.color = '#FF9F55')}
                            onMouseLeave={e => (e.currentTarget.style.textDecoration = 'none', e.currentTarget.style.color = '#E8E8E8')}
                          >
                            {row.name}
                          </Link>
                        </td>
                        <td style={{ padding: '10px 16px' }}>
                          <VerdictPill verdict={row.verdict} />
                        </td>
                        <td style={{ padding: '10px 16px', fontFamily: 'monospace', fontSize: '13px', color: row.gapsCount > 0 ? '#ef4444' : '#6B6B6B' }}>
                          {row.gapsCount}
                        </td>
                        <td style={{ padding: '10px 16px', fontFamily: 'monospace', fontSize: '13px', color: row.sanctionsHits > 0 ? '#ef4444' : '#6B6B6B' }}>
                          {row.sanctionsHits}
                        </td>
                        <td style={{ padding: '10px 16px', fontFamily: 'monospace', fontSize: '12px', color: '#6B6B6B', whiteSpace: 'nowrap' }}>
                          {row.lastRunAt ? new Date(row.lastRunAt).toLocaleString() : '—'}
                        </td>
                        <td style={{ padding: '10px 16px' }}>
                          <div className="flex items-center gap-2">
                            <button
                              disabled={!row.proofPackAvailable}
                              onClick={() => code && downloadProofPack(row.launchId, code)}
                              style={{
                                fontFamily: 'monospace',
                                fontSize: '11px',
                                padding: '4px 10px',
                                borderRadius: '6px',
                                border: row.proofPackAvailable ? '1px solid rgba(255,159,85,0.4)' : '1px solid rgba(255,255,255,0.08)',
                                background: row.proofPackAvailable ? 'rgba(255,159,85,0.08)' : 'transparent',
                                color: row.proofPackAvailable ? '#FF9F55' : '#3A3A3A',
                                cursor: row.proofPackAvailable ? 'pointer' : 'not-allowed',
                                whiteSpace: 'nowrap',
                                transition: 'background 0.15s',
                              }}
                            >
                              ⬇ Proof Pack
                            </button>
                            <button
                              onClick={() => code && navigate(`/jurisdictions/${code}/launches/${row.launchId}`)}
                              style={{
                                fontFamily: 'monospace',
                                fontSize: '11px',
                                padding: '4px 10px',
                                borderRadius: '6px',
                                border: '1px solid rgba(255,255,255,0.1)',
                                background: 'transparent',
                                color: '#9B9B9B',
                                cursor: 'pointer',
                                whiteSpace: 'nowrap',
                                transition: 'background 0.15s, color 0.15s',
                              }}
                              onMouseEnter={e => { e.currentTarget.style.background = 'rgba(255,255,255,0.06)'; e.currentTarget.style.color = '#E8E8E8'; }}
                              onMouseLeave={e => { e.currentTarget.style.background = 'transparent'; e.currentTarget.style.color = '#9B9B9B'; }}
                            >
                              📊 Graph
                            </button>
                          </div>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        </>
      )}
    </div>
  );
}
