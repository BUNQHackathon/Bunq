import { useState, useEffect, useCallback } from 'react';
import { useNavigate, useLocation } from 'react-router-dom';

export type Mode = 'expansion' | 'regulator';

const STORAGE_KEY = 'launchlens.mode';
const EVENT_NAME = 'launchlens:mode-change';

function readMode(): Mode {
  if (typeof window === 'undefined') return 'expansion';
  const stored = localStorage.getItem(STORAGE_KEY);
  return stored === 'regulator' ? 'regulator' : 'expansion';
}

export function useMode(): [Mode, (m: Mode) => void] {
  const [mode, setModeState] = useState<Mode>(readMode);

  const setMode = useCallback((m: Mode) => {
    localStorage.setItem(STORAGE_KEY, m);
    window.dispatchEvent(new CustomEvent(EVENT_NAME, { detail: m }));
    setModeState(m);
  }, []);

  useEffect(() => {
    const onStorage = (e: StorageEvent) => {
      if (e.key === STORAGE_KEY) {
        setModeState(e.newValue === 'regulator' ? 'regulator' : 'expansion');
      }
    };
    const onCustom = (e: Event) => {
      const detail = (e as CustomEvent<Mode>).detail;
      setModeState(detail === 'regulator' ? 'regulator' : 'expansion');
    };
    window.addEventListener('storage', onStorage);
    window.addEventListener(EVENT_NAME, onCustom);
    return () => {
      window.removeEventListener('storage', onStorage);
      window.removeEventListener(EVENT_NAME, onCustom);
    };
  }, []);

  return [mode, setMode];
}

export default function ModeToggle() {
  const [mode, setModeRaw] = useMode();
  const navigate = useNavigate();
  const location = useLocation();

  const handleSelect = (m: Mode) => {
    setModeRaw(m);
    const path = location.pathname;
    if (m === 'expansion' && path.startsWith('/jurisdictions')) {
      navigate('/launches');
    } else if (m === 'regulator' && path.startsWith('/launches')) {
      navigate('/jurisdictions');
    }
  };

  const segmentStyle = (active: boolean): React.CSSProperties => ({
    padding: '4px 12px',
    borderRadius: '9999px',
    fontSize: '12px',
    fontWeight: 500,
    cursor: 'pointer',
    transition: 'all 0.15s',
    background: active ? 'rgba(255,120,25,0.14)' : 'transparent',
    color: active ? '#FF9F55' : '#6B6B6B',
    border: 'none',
    outline: 'none',
  });

  return (
    <div
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        borderRadius: '9999px',
        padding: '2px',
        background: '#141414',
        border: '1px solid rgba(255,255,255,0.08)',
      }}
    >
      <button
        style={segmentStyle(mode === 'expansion')}
        onClick={() => handleSelect('expansion')}
        onMouseEnter={(e) => { if (mode !== 'expansion') (e.currentTarget as HTMLButtonElement).style.color = '#E8E8E8'; }}
        onMouseLeave={(e) => { if (mode !== 'expansion') (e.currentTarget as HTMLButtonElement).style.color = '#6B6B6B'; }}
      >
        🚀 Expansion
      </button>
      <button
        style={segmentStyle(mode === 'regulator')}
        onClick={() => handleSelect('regulator')}
        onMouseEnter={(e) => { if (mode !== 'regulator') (e.currentTarget as HTMLButtonElement).style.color = '#E8E8E8'; }}
        onMouseLeave={(e) => { if (mode !== 'regulator') (e.currentTarget as HTMLButtonElement).style.color = '#6B6B6B'; }}
      >
        ⚖️ Regulator
      </button>
    </div>
  );
}
