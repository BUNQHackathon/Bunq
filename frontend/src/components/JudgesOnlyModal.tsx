import { useEffect } from 'react';
import { useNavigate } from 'react-router-dom';

interface Props {
  open: boolean;
  onClose: () => void;
  title?: string;
  message?: string;
}

export default function JudgesOnlyModal({ open, onClose, title, message }: Props) {
  const navigate = useNavigate();

  useEffect(() => {
    if (!open) return;
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    document.addEventListener('keydown', handler);
    return () => document.removeEventListener('keydown', handler);
  }, [open, onClose]);

  if (!open) return null;

  return (
    <div
      onClick={onClose}
      style={{
        position: 'fixed',
        inset: 0,
        background: 'rgba(0,0,0,0.6)',
        backdropFilter: 'blur(4px)',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        zIndex: 1000,
      }}
    >
      <div
        onClick={(e) => e.stopPropagation()}
        style={{
          background: 'var(--bg-1)',
          border: '1px solid var(--line-1)',
          borderRadius: 14,
          padding: 28,
          maxWidth: 380,
          width: '100%',
          fontFamily: 'var(--ui)',
        }}
      >
        <div style={{ fontSize: 18, fontWeight: 600, color: 'var(--ink-0)' }}>
          {title ?? 'Bunq judges only'}
        </div>
        <p style={{ fontSize: 13, color: 'var(--ink-2)', margin: '12px 0 24px' }}>
          {message ?? 'This action requires an access token. Sign in to continue.'}
        </p>
        <div style={{ display: 'flex', gap: 8 }}>
          <button className="btn" onClick={onClose}>
            Close
          </button>
          <button className="btn btn--orange" onClick={() => navigate('/login')}>
            Sign in
          </button>
        </div>
      </div>
    </div>
  );
}
