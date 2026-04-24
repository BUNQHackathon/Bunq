import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { createLaunch, addJurisdiction, JURISDICTION_CATALOG, type LaunchKind } from '../api/launch';
import KindBadge from '../components/KindBadge';

const DEMO_MARKETS = ['NL', 'DE', 'FR', 'GB', 'US', 'IE'];
const DEMO_CATALOG = JURISDICTION_CATALOG.filter((j) => DEMO_MARKETS.includes(j.code));

interface WizardState {
  step: 1 | 2 | 3;
  name: string;
  kind: LaunchKind;
  brief: string;
  license: string;
  markets: string[];
  submitting: boolean;
  error: string | null;
}

const INITIAL: WizardState = {
  step: 1,
  name: '',
  kind: 'PRODUCT',
  brief: '',
  license: '',
  markets: [],
  submitting: false,
  error: null,
};

const KINDS: LaunchKind[] = ['PRODUCT', 'POLICY', 'PROCESS'];

export default function LaunchNewPage() {
  const navigate = useNavigate();
  const [state, setState] = useState<WizardState>(INITIAL);

  const set = (partial: Partial<WizardState>) =>
    setState((prev) => ({ ...prev, ...partial }));

  const nameValid = state.name.trim().length >= 2;
  const briefValid = state.brief.trim().length >= 10;
  const marketsValid = state.markets.length >= 1;

  function toggleMarket(code: string) {
    set({
      markets: state.markets.includes(code)
        ? state.markets.filter((c) => c !== code)
        : [...state.markets, code],
    });
  }

  async function handleSubmit() {
    set({ submitting: true, error: null });
    let launch;
    try {
      launch = await createLaunch({
        name: state.name.trim(),
        brief: state.brief.trim(),
        license: state.license.trim() || undefined,
        kind: state.kind,
        markets: state.markets,
      });
    } catch (err) {
      set({
        submitting: false,
        error: err instanceof Error ? err.message : 'Failed to create launch',
      });
      return;
    }

    await Promise.all(
      state.markets.map((code) =>
        addJurisdiction(launch.id, code).catch((e) =>
          console.warn(`addJurisdiction(${code}) failed:`, e),
        ),
      ),
    );

    navigate(`/launches/${launch.id}`);
  }

  const inputStyle: React.CSSProperties = {
    background: '#141414',
    border: '1px solid rgba(255,255,255,0.08)',
    borderRadius: '8px',
    padding: '8px 12px',
    fontSize: '14px',
    color: '#E8E8E8',
    outline: 'none',
    width: '100%',
    boxSizing: 'border-box',
  };

  const inputFocusHandlers = {
    onFocus: (e: React.FocusEvent<HTMLInputElement | HTMLTextAreaElement>) => {
      e.currentTarget.style.borderColor = 'rgba(255,120,25,0.4)';
    },
    onBlur: (e: React.FocusEvent<HTMLInputElement | HTMLTextAreaElement>) => {
      e.currentTarget.style.borderColor = 'rgba(255,255,255,0.08)';
    },
  };

  return (
    <div className="min-h-screen px-6 py-10 max-w-3xl mx-auto" style={{ color: '#E8E8E8' }}>
      <div className="mb-8">
        <Link
          to="/launches"
          className="inline-flex items-center gap-1 text-[13px] mb-5 transition-colors"
          style={{ color: '#6B6B6B', textDecoration: 'none' }}
          onMouseEnter={(e) => { (e.currentTarget as HTMLAnchorElement).style.color = '#E8E8E8'; }}
          onMouseLeave={(e) => { (e.currentTarget as HTMLAnchorElement).style.color = '#6B6B6B'; }}
        >
          ← Back to launches
        </Link>

        <div className="flex items-start justify-between gap-4 mt-2">
          <div>
            <h1 className="text-2xl font-semibold text-white mb-1">New Launch</h1>
            <p className="font-mono text-[11px] uppercase tracking-wider" style={{ color: '#6B6B6B' }}>
              Step {state.step} of 3
            </p>
          </div>
        </div>

        <div className="flex gap-2 mt-4">
          {([1, 2, 3] as const).map((s) => (
            <div
              key={s}
              className="h-1 flex-1 rounded-full transition-all"
              style={{
                background: s <= state.step ? '#FF9F55' : 'rgba(255,255,255,0.08)',
              }}
            />
          ))}
        </div>
      </div>

      <div
        className="rounded-xl p-6"
        style={{
          background: '#0D0D0D',
          border: '1px solid rgba(255,255,255,0.06)',
        }}
      >
        {state.step === 1 && (
          <div className="flex flex-col gap-6">
            <div>
              <label className="block text-[12px] font-mono uppercase tracking-wider mb-2" style={{ color: '#6B6B6B' }}>
                Launch name *
              </label>
              <input
                type="text"
                value={state.name}
                onChange={(e) => set({ name: e.target.value })}
                placeholder="e.g. BNPL Europe Rollout"
                style={inputStyle}
                {...inputFocusHandlers}
                disabled={state.submitting}
              />
              {state.name.length > 0 && !nameValid && (
                <p className="text-[11px] mt-1" style={{ color: '#E05050' }}>
                  Name must be at least 2 characters
                </p>
              )}
            </div>

            <div>
              <label className="block text-[12px] font-mono uppercase tracking-wider mb-2" style={{ color: '#6B6B6B' }}>
                Kind
              </label>
              <div className="inline-flex rounded-full overflow-hidden" style={{ border: '1px solid rgba(255,255,255,0.08)' }}>
                {KINDS.map((k) => {
                  const active = state.kind === k;
                  return (
                    <button
                      key={k}
                      onClick={() => set({ kind: k })}
                      className="px-4 py-1.5 text-[12px] font-mono uppercase transition-all"
                      style={{
                        background: active ? 'rgba(255,120,25,0.14)' : 'transparent',
                        color: active ? '#FF9F55' : '#6B6B6B',
                        border: 'none',
                        cursor: 'pointer',
                      }}
                      onMouseEnter={(e) => {
                        if (!active) (e.currentTarget as HTMLButtonElement).style.color = '#E8E8E8';
                      }}
                      onMouseLeave={(e) => {
                        if (!active) (e.currentTarget as HTMLButtonElement).style.color = '#6B6B6B';
                      }}
                    >
                      {k}
                    </button>
                  );
                })}
              </div>

              <div className="mt-3 flex items-center gap-2">
                <span className="text-[12px]" style={{ color: '#6B6B6B' }}>Preview:</span>
                <KindBadge kind={state.kind} />
              </div>
            </div>

            <div className="flex justify-end">
              <button
                onClick={() => set({ step: 2 })}
                disabled={!nameValid}
                className="px-5 py-2 rounded-xl text-[13px] font-medium transition-all"
                style={{
                  background: nameValid ? 'rgba(255,120,25,0.14)' : 'rgba(255,255,255,0.04)',
                  border: nameValid ? '1px solid rgba(255,120,25,0.35)' : '1px solid rgba(255,255,255,0.06)',
                  color: nameValid ? '#FF9F55' : '#4B4B4B',
                  cursor: nameValid ? 'pointer' : 'not-allowed',
                  opacity: nameValid ? 1 : 0.5,
                  pointerEvents: nameValid ? 'auto' : 'none',
                }}
              >
                Next →
              </button>
            </div>
          </div>
        )}

        {state.step === 2 && (
          <div className="flex flex-col gap-6">
            <div>
              <label className="block text-[12px] font-mono uppercase tracking-wider mb-2" style={{ color: '#6B6B6B' }}>
                Brief *
              </label>
              <textarea
                value={state.brief}
                onChange={(e) => set({ brief: e.target.value })}
                placeholder="Describe the purpose, scope, and goals of this launch..."
                rows={4}
                style={{ ...inputStyle, resize: 'vertical' }}
                onFocus={(e) => { e.currentTarget.style.borderColor = 'rgba(255,120,25,0.4)'; }}
                onBlur={(e) => { e.currentTarget.style.borderColor = 'rgba(255,255,255,0.08)'; }}
                disabled={state.submitting}
              />
              {state.brief.length > 0 && !briefValid && (
                <p className="text-[11px] mt-1" style={{ color: '#E05050' }}>
                  Brief must be at least 10 characters
                </p>
              )}
            </div>

            <div>
              <label className="block text-[12px] font-mono uppercase tracking-wider mb-2" style={{ color: '#6B6B6B' }}>
                License <span style={{ color: '#3B3B3B' }}>(optional)</span>
              </label>
              <input
                type="text"
                value={state.license}
                onChange={(e) => set({ license: e.target.value })}
                placeholder="e.g. EMI, Banking, PSD2"
                style={inputStyle}
                {...inputFocusHandlers}
                disabled={state.submitting}
              />
            </div>

            <div className="flex justify-between">
              <button
                onClick={() => set({ step: 1 })}
                className="px-4 py-2 text-[13px] transition-all"
                style={{ color: '#6B6B6B', background: 'none', border: 'none', cursor: 'pointer' }}
                onMouseEnter={(e) => { (e.currentTarget as HTMLButtonElement).style.color = '#E8E8E8'; }}
                onMouseLeave={(e) => { (e.currentTarget as HTMLButtonElement).style.color = '#6B6B6B'; }}
              >
                ← Back
              </button>
              <button
                onClick={() => set({ step: 3 })}
                disabled={!briefValid}
                className="px-5 py-2 rounded-xl text-[13px] font-medium transition-all"
                style={{
                  background: briefValid ? 'rgba(255,120,25,0.14)' : 'rgba(255,255,255,0.04)',
                  border: briefValid ? '1px solid rgba(255,120,25,0.35)' : '1px solid rgba(255,255,255,0.06)',
                  color: briefValid ? '#FF9F55' : '#4B4B4B',
                  cursor: briefValid ? 'pointer' : 'not-allowed',
                  opacity: briefValid ? 1 : 0.5,
                  pointerEvents: briefValid ? 'auto' : 'none',
                }}
              >
                Next →
              </button>
            </div>
          </div>
        )}

        {state.step === 3 && (
          <div className="flex flex-col gap-6">
            <div>
              <label className="block text-[12px] font-mono uppercase tracking-wider mb-3" style={{ color: '#6B6B6B' }}>
                Target markets * <span style={{ color: '#3B3B3B' }}>— pick at least one</span>
              </label>
              <div className="grid grid-cols-2 sm:grid-cols-3 gap-3">
                {DEMO_CATALOG.map((j) => {
                  const selected = state.markets.includes(j.code);
                  return (
                    <button
                      key={j.code}
                      onClick={() => toggleMarket(j.code)}
                      disabled={state.submitting}
                      className="flex items-center gap-2 rounded-xl px-3 py-3 text-left transition-all"
                      style={{
                        background: selected ? 'rgba(255,120,25,0.08)' : '#141414',
                        border: selected
                          ? '1px solid rgba(255,120,25,0.4)'
                          : '1px solid rgba(255,255,255,0.08)',
                        cursor: state.submitting ? 'not-allowed' : 'pointer',
                        opacity: state.submitting ? 0.6 : 1,
                      }}
                    >
                      <span className="text-[18px]">{j.flag}</span>
                      <div className="flex flex-col min-w-0">
                        <span className="text-[13px] font-medium truncate" style={{ color: selected ? '#FF9F55' : '#E8E8E8' }}>
                          {j.name}
                        </span>
                        <span className="text-[10px] font-mono" style={{ color: '#6B6B6B' }}>
                          {j.code}
                        </span>
                      </div>
                    </button>
                  );
                })}
              </div>
              {state.markets.length === 0 && (
                <p className="text-[11px] mt-2" style={{ color: '#6B6B6B' }}>
                  Select at least one market to continue
                </p>
              )}
            </div>

            {state.error && (
              <div
                className="rounded-lg px-4 py-3"
                style={{
                  background: 'rgba(224,80,80,0.08)',
                  border: '1px solid rgba(224,80,80,0.25)',
                }}
              >
                <p className="text-[13px]" style={{ color: '#E05050' }}>{state.error}</p>
              </div>
            )}

            <div className="flex justify-between">
              <button
                onClick={() => set({ step: 2 })}
                disabled={state.submitting}
                className="px-4 py-2 text-[13px] transition-all"
                style={{
                  color: '#6B6B6B',
                  background: 'none',
                  border: 'none',
                  cursor: state.submitting ? 'not-allowed' : 'pointer',
                  opacity: state.submitting ? 0.5 : 1,
                }}
                onMouseEnter={(e) => {
                  if (!state.submitting) (e.currentTarget as HTMLButtonElement).style.color = '#E8E8E8';
                }}
                onMouseLeave={(e) => {
                  (e.currentTarget as HTMLButtonElement).style.color = '#6B6B6B';
                }}
              >
                ← Back
              </button>

              <button
                onClick={handleSubmit}
                disabled={!marketsValid || state.submitting}
                className="px-5 py-2 rounded-xl text-[13px] font-medium transition-all flex items-center gap-2"
                style={{
                  background: marketsValid && !state.submitting ? 'rgba(255,120,25,0.14)' : 'rgba(255,255,255,0.04)',
                  border: marketsValid && !state.submitting ? '1px solid rgba(255,120,25,0.35)' : '1px solid rgba(255,255,255,0.06)',
                  color: marketsValid && !state.submitting ? '#FF9F55' : '#4B4B4B',
                  cursor: marketsValid && !state.submitting ? 'pointer' : 'not-allowed',
                  opacity: marketsValid && !state.submitting ? 1 : 0.5,
                  pointerEvents: marketsValid && !state.submitting ? 'auto' : 'none',
                }}
              >
                {state.submitting && (
                  <span
                    className="inline-block w-3 h-3 rounded-full border-2 border-t-transparent animate-spin"
                    style={{ borderColor: 'rgba(255,159,85,0.4)', borderTopColor: 'transparent' }}
                  />
                )}
                {state.submitting ? 'Creating…' : 'Create launch'}
              </button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
