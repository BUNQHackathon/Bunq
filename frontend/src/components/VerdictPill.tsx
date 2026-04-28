import type { Verdict } from '../api/launch';

interface Props { verdict: Verdict; showEmoji?: boolean; className?: string; }

// Hex values mirror the C2 warm palette used for map country fills
// (see MOCK_COUNTRY_COLOR in api/mockCountries.ts). Single source of
// truth for verdict colours across pills, legend dots, filter chips,
// and row-status pills.
const CONFIG: Record<Verdict, { hex: string; bg: string; border: string; emoji: string; label: string }> = {
  GREEN: { hex: '#cfb275', bg: 'rgba(207,178,117,0.12)', border: '1px solid rgba(207,178,117,0.3)', emoji: '🟢', label: 'Compliant' },
  AMBER: { hex: '#b87538', bg: 'rgba(184,117,56,0.12)', border: '1px solid rgba(184,117,56,0.3)', emoji: '🟡', label: 'Needs review' },
  RED: { hex: '#a83820', bg: 'rgba(168,56,32,0.12)', border: '1px solid rgba(168,56,32,0.3)', emoji: '🔴', label: 'Breach' },
  PENDING: { hex: '#6B6B6B', bg: 'rgba(255,255,255,0.04)', border: '1px solid rgba(255,255,255,0.08)', emoji: '⏳', label: 'Pending' },
  UNKNOWN: { hex: '#9ca3af', bg: 'rgba(156,163,175,0.10)', border: '1px solid rgba(156,163,175,0.25)', emoji: '⚪', label: 'Unknown' },
};

/** Neutral grey used for the FAILED run state (not a verdict). */
export const FAILED_COLOR = '#6b6b6b';

export function verdictToHex(verdict: Verdict): string {
  return CONFIG[verdict].hex;
}

export default function VerdictPill({ verdict, showEmoji = true, className }: Props) {
  const c = CONFIG[verdict];
  return (
    <span
      className={`inline-flex items-center gap-1 rounded-full px-2.5 py-0.5 text-[11px] font-mono${className ? ' ' + className : ''}`}
      style={{ background: c.bg, color: c.hex, border: c.border }}
    >
      {showEmoji && <span>{c.emoji}</span>}
      {c.label}
    </span>
  );
}
