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

const inputStyle: React.CSSProperties = {
  background: 'var(--bg-2)',
  border: '1px solid var(--line-1)',
  borderRadius: 'var(--r-md)',
  padding: '12px 14px',
  font: '400 14px/1.4 var(--ui)',
  color: 'var(--ink-0)',
  outline: 'none',
  width: '100%',
  boxSizing: 'border-box',
};

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

  const handleFocus = (e: React.FocusEvent<HTMLInputElement | HTMLTextAreaElement>) => {
    e.currentTarget.style.borderColor = 'var(--orange)';
  };
  const handleBlur = (e: React.FocusEvent<HTMLInputElement | HTMLTextAreaElement>) => {
    e.currentTarget.style.borderColor = 'var(--line-1)';
  };

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

  return (
    <div style={{ maxWidth: 720, margin: '0 auto', padding: '40px 24px 80px', fontFamily: 'var(--ui)' }}>

      {/* Header */}
      <Link to="/launches" className="mono-label" style={{ textDecoration: 'none' }}>
        ← Back to launches
      </Link>
      <h1 className="serif-display" style={{ fontSize: 40, marginTop: 8, marginBottom: 0 }}>
        New launch<span style={{ color: 'var(--orange)' }}>.</span>
      </h1>
      <div className="mono-label" style={{ marginTop: 4 }}>STEP {state.step} OF 3</div>

      {/* Progress bars */}
      <div style={{ display: 'flex', gap: 6, margin: '24px 0 32px' }}>
        {[1, 2, 3].map((i) => (
          <div
            key={i}
            style={{
              flex: 1,
              height: 2,
              borderRadius: 2,
              background: i <= state.step ? 'var(--orange)' : 'var(--line-1)',
            }}
          />
        ))}
      </div>

      {/* Form card */}
      <div
        style={{
          background: 'var(--bg-1)',
          border: '1px solid var(--line-1)',
          borderRadius: 'var(--r-lg)',
          padding: 32,
        }}
      >
        {/* ── Step 1: name + kind ────────────────────────────────────────── */}
        {state.step === 1 && (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 24 }}>
            <div>
              <label
                className="mono-label"
                style={{ display: 'block', marginBottom: 8 }}
              >
                Launch name *
              </label>
              <input
                type="text"
                value={state.name}
                onChange={(e) => set({ name: e.target.value })}
                placeholder="e.g. BNPL Europe Rollout"
                style={inputStyle}
                onFocus={handleFocus}
                onBlur={handleBlur}
                disabled={state.submitting}
              />
              {state.name.length > 0 && !nameValid && (
                <p style={{ fontSize: 11, marginTop: 4, color: 'var(--danger, #d94a4a)' }}>
                  Name must be at least 2 characters
                </p>
              )}
            </div>

            <div>
              <label className="mono-label" style={{ display: 'block', marginBottom: 8 }}>
                Kind
              </label>
              <div style={{ display: 'inline-flex', gap: 6 }}>
                {KINDS.map((k) => {
                  const active = state.kind === k;
                  return (
                    <button
                      key={k}
                      onClick={() => set({ kind: k })}
                      className={`chip chip--sm${active ? ' chip--orange' : ''}`}
                    >
                      {k}
                    </button>
                  );
                })}
              </div>
              <div style={{ marginTop: 12, display: 'flex', alignItems: 'center', gap: 8 }}>
                <span className="mono-label">Preview:</span>
                <KindBadge kind={state.kind} />
              </div>
            </div>

            <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
              <button
                className="btn btn--orange"
                onClick={() => set({ step: 2 })}
                disabled={!nameValid}
                style={{ opacity: nameValid ? 1 : 0.4, cursor: nameValid ? 'pointer' : 'not-allowed' }}
              >
                Next →
              </button>
            </div>
          </div>
        )}

        {/* ── Step 2: brief + license ────────────────────────────────────── */}
        {state.step === 2 && (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 24 }}>
            <div>
              <label className="mono-label" style={{ display: 'block', marginBottom: 8 }}>
                Brief *
              </label>
              <textarea
                value={state.brief}
                onChange={(e) => set({ brief: e.target.value })}
                placeholder="Describe the purpose, scope, and goals of this launch..."
                style={{ ...inputStyle, minHeight: 120, resize: 'vertical' }}
                onFocus={handleFocus}
                onBlur={handleBlur}
                disabled={state.submitting}
              />
              {state.brief.length > 0 && !briefValid && (
                <p style={{ fontSize: 11, marginTop: 4, color: 'var(--danger, #d94a4a)' }}>
                  Brief must be at least 10 characters
                </p>
              )}
            </div>

            <div>
              <label className="mono-label" style={{ display: 'block', marginBottom: 8 }}>
                License{' '}
                <span style={{ color: 'var(--ink-3)', textTransform: 'none', letterSpacing: 0, fontFamily: 'var(--ui)' }}>
                  (optional)
                </span>
              </label>
              <input
                type="text"
                value={state.license}
                onChange={(e) => set({ license: e.target.value })}
                placeholder="e.g. EMI, Banking, PSD2"
                style={inputStyle}
                onFocus={handleFocus}
                onBlur={handleBlur}
                disabled={state.submitting}
              />
            </div>

            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <button
                className="btn btn--ghost btn--sm"
                onClick={() => set({ step: 1 })}
              >
                ← Back
              </button>
              <button
                className="btn btn--orange"
                onClick={() => set({ step: 3 })}
                disabled={!briefValid}
                style={{ opacity: briefValid ? 1 : 0.4, cursor: briefValid ? 'pointer' : 'not-allowed' }}
              >
                Next →
              </button>
            </div>
          </div>
        )}

        {/* ── Step 3: markets ────────────────────────────────────────────── */}
        {state.step === 3 && (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 24 }}>
            <div>
              <label className="mono-label" style={{ display: 'block', marginBottom: 12 }}>
                Target markets *{' '}
                <span style={{ color: 'var(--ink-3)', textTransform: 'none', letterSpacing: 0, fontFamily: 'var(--ui)' }}>
                  — pick at least one
                </span>
              </label>
              <div style={{ display: 'flex', flexWrap: 'wrap', gap: 10 }}>
                {DEMO_CATALOG.map((j) => {
                  const selected = state.markets.includes(j.code);
                  return (
                    <button
                      key={j.code}
                      onClick={() => toggleMarket(j.code)}
                      disabled={state.submitting}
                      className={`chip${selected ? ' chip--orange' : ''}`}
                      style={{
                        opacity: state.submitting ? 0.4 : 1,
                        cursor: state.submitting ? 'not-allowed' : 'pointer',
                      }}
                    >
                      <span style={{ fontSize: 16 }}>{j.flag}</span>
                      <span>{j.name}</span>
                      <span className="mono-label" style={{ letterSpacing: '0.04em' }}>{j.code}</span>
                    </button>
                  );
                })}
              </div>
              {state.markets.length === 0 && (
                <p className="mono-label" style={{ marginTop: 8 }}>
                  Select at least one market to continue
                </p>
              )}
            </div>

            {state.error && (
              <div
                style={{
                  background: 'rgba(217,74,74,0.08)',
                  border: '1px solid rgba(217,74,74,0.3)',
                  color: 'var(--danger, #d94a4a)',
                  padding: '10px 14px',
                  borderRadius: 'var(--r-md)',
                }}
              >
                {state.error}
              </div>
            )}

            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <button
                className="btn btn--ghost btn--sm"
                onClick={() => set({ step: 2 })}
                disabled={state.submitting}
                style={{ opacity: state.submitting ? 0.4 : 1, cursor: state.submitting ? 'not-allowed' : 'pointer' }}
              >
                ← Back
              </button>

              <button
                className="btn btn--orange"
                onClick={handleSubmit}
                disabled={!marketsValid || state.submitting}
                style={{
                  opacity: marketsValid && !state.submitting ? 1 : 0.4,
                  cursor: marketsValid && !state.submitting ? 'pointer' : 'not-allowed',
                }}
              >
                {state.submitting && (
                  <span
                    style={{
                      display: 'inline-block',
                      width: 12,
                      height: 12,
                      borderRadius: '50%',
                      border: '2px solid var(--orange-ink)',
                      borderTopColor: 'transparent',
                      animation: 'spin 0.7s linear infinite',
                    }}
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
