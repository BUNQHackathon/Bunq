import type { Verdict } from '../api/launch';

interface Props { verdict: Verdict; showEmoji?: boolean; className?: string; }

const CONFIG: Record<Verdict, { hex: string; bg: string; border: string; emoji: string; label: string }> = {
  GREEN:   { hex: '#22c55e', bg: 'rgba(34,197,94,0.12)',   border: '1px solid rgba(34,197,94,0.3)',    emoji: '🟢', label: 'GREEN'   },
  AMBER:   { hex: '#f59e0b', bg: 'rgba(245,158,11,0.12)',  border: '1px solid rgba(245,158,11,0.3)',   emoji: '🟡', label: 'AMBER'   },
  RED:     { hex: '#ef4444', bg: 'rgba(239,68,68,0.12)',   border: '1px solid rgba(239,68,68,0.3)',    emoji: '🔴', label: 'RED'     },
  PENDING: { hex: '#6B6B6B', bg: 'rgba(255,255,255,0.04)', border: '1px solid rgba(255,255,255,0.08)', emoji: '⏳', label: 'PENDING' },
  UNKNOWN: { hex: '#9ca3af', bg: 'rgba(156,163,175,0.10)', border: '1px solid rgba(156,163,175,0.25)', emoji: '⚪', label: 'UNKNOWN' },
};

export function verdictToHex(verdict: Verdict): string {
  return CONFIG[verdict].hex;
}

export default function VerdictPill({ verdict, showEmoji = true, className }: Props) {
  const c = CONFIG[verdict];
  return (
    <span
      className={`inline-flex items-center gap-1 rounded-full px-2.5 py-0.5 text-[11px] font-mono uppercase${className ? ' ' + className : ''}`}
      style={{ background: c.bg, color: c.hex, border: c.border }}
    >
      {showEmoji && <span>{c.emoji}</span>}
      {c.label}
    </span>
  );
}
