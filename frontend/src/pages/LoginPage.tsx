import { useState, type FormEvent } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { API_BASE } from '../api/client';
import { useAuth } from '../auth/useAuth';

export default function LoginPage() {
  const [token, setToken] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const { login } = useAuth();
  const navigate = useNavigate();
  const [params] = useSearchParams();

  async function handleSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setError(null);
    setPending(true);
    try {
      const res = await fetch(`${API_BASE}/auth/check`, {
        method: 'POST',
        headers: { Authorization: `Bearer ${token}` },
      });
      if (res.status === 401) {
        setError('Invalid token');
        return;
      }
      if (!res.ok) {
        setError('Could not reach server');
        return;
      }
      login(token);
      const redirect = params.get('redirect') ?? '/';
      navigate(redirect, { replace: true });
    } catch {
      setError('Could not reach server');
    } finally {
      setPending(false);
    }
  }

  return (
    <div style={{
      minHeight: '100vh',
      background: 'var(--bg-0)',
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'center',
      fontFamily: 'var(--ui)',
    }}>
      <div style={{
        width: '100%',
        maxWidth: 380,
        background: 'var(--bg-1)',
        border: '1px solid var(--line-1)',
        borderRadius: 14,
        padding: '32px 28px',
      }}>
        <button
          type="button"
          onClick={() => navigate(-1)}
          style={{
            display: 'inline-flex',
            alignItems: 'center',
            gap: 4,
            background: 'none',
            border: 'none',
            padding: 0,
            margin: '0 0 16px',
            color: 'var(--ink-2)',
            fontFamily: 'var(--ui)',
            fontSize: 12,
            cursor: 'pointer',
          }}
        >
          ← Back
        </button>
        <h1 style={{
          margin: '0 0 4px',
          fontSize: 20,
          fontWeight: 600,
          color: 'var(--ink-0)',
          letterSpacing: '-0.02em',
        }}>Sign in</h1>
        <p style={{
          margin: '0 0 24px',
          fontSize: 13,
          color: 'var(--ink-2)',
        }}>Enter your access token</p>

        <form onSubmit={handleSubmit}>
          <label style={{
            display: 'block',
            fontSize: 12,
            fontWeight: 500,
            color: 'var(--ink-1)',
            marginBottom: 6,
          }}>
            Access token
          </label>
          <input
            type="password"
            value={token}
            onChange={e => setToken(e.target.value)}
            autoComplete="current-password"
            required
            style={{
              display: 'block',
              width: '100%',
              background: 'var(--bg-inset)',
              border: '1px solid var(--line-1)',
              borderRadius: 'var(--r-sm)',
              color: 'var(--ink-0)',
              fontFamily: 'var(--ui)',
              fontSize: 13,
              padding: '9px 11px',
              outline: 'none',
              boxSizing: 'border-box',
              marginBottom: error ? 6 : 20,
            }}
          />
          {error && (
            <p style={{
              margin: '0 0 16px',
              fontSize: 12,
              color: 'var(--danger)',
            }}>{error}</p>
          )}
          <button
            type="submit"
            disabled={pending}
            className="btn btn--orange"
            style={{ width: '100%', justifyContent: 'center', opacity: pending ? 0.65 : 1 }}
          >
            {pending ? 'Checking…' : 'Sign in'}
          </button>
        </form>
      </div>
    </div>
  );
}
