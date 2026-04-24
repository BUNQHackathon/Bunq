import type { LaunchKind } from '../api/launch';

interface Props { kind: LaunchKind; className?: string; }

const STYLES: Record<LaunchKind, { background: string; color: string; border: string }> = {
  PRODUCT: { background: 'rgba(110,183,232,0.14)', color: '#6EB7E8', border: '1px solid rgba(110,183,232,0.3)' },
  POLICY:  { background: 'rgba(176,138,255,0.14)', color: '#B08AFF', border: '1px solid rgba(176,138,255,0.3)' },
  PROCESS: { background: 'rgba(255,159,85,0.14)',  color: '#FF9F55', border: '1px solid rgba(255,159,85,0.3)'  },
};

export default function KindBadge({ kind, className }: Props) {
  return (
    <span
      className={`inline-flex items-center rounded-full px-2.5 py-0.5 text-[10px] font-mono uppercase${className ? ' ' + className : ''}`}
      style={STYLES[kind]}
    >
      {kind}
    </span>
  );
}
