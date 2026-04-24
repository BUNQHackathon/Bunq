import { useState, useEffect, useRef } from 'react';
import type { GraphRef } from '../api/chat';
import { jurisdictionFlag } from '../api/launch';

interface Props { refs: GraphRef[]; onOpen?: (ref: GraphRef) => void; className?: string; }

const chipBase: React.CSSProperties = {
  display: 'inline-flex',
  alignItems: 'center',
  gap: '6px',
  borderRadius: '9999px',
  padding: '4px 10px',
  fontSize: '11px',
  background: 'rgba(255,255,255,0.04)',
  border: '1px solid rgba(255,255,255,0.08)',
  cursor: 'pointer',
  color: 'rgba(255,255,255,0.7)',
  transition: 'all 0.15s',
};

const chipHover: React.CSSProperties = {
  ...chipBase,
  background: 'rgba(255,120,25,0.08)',
  border: '1px solid rgba(255,120,25,0.3)',
};

function Chip({ ref: _ref, graphRef, onOpen }: { ref?: React.Ref<HTMLButtonElement>; graphRef: GraphRef; onOpen?: (r: GraphRef) => void }) {
  const [hovered, setHovered] = useState(false);
  return (
    <button
      style={hovered ? chipHover : chipBase}
      onClick={() => onOpen?.(graphRef)}
      onMouseEnter={() => setHovered(true)}
      onMouseLeave={() => setHovered(false)}
    >
      📊 {graphRef.launchName} · {jurisdictionFlag(graphRef.jurisdictionCode)} {graphRef.jurisdictionCode} · Open graph →
    </button>
  );
}

export default function GraphRefChips({ refs, onOpen, className }: Props) {
  const [popoverOpen, setPopoverOpen] = useState(false);
  const moreRef = useRef<HTMLButtonElement>(null);
  const popoverRef = useRef<HTMLDivElement>(null);

  const visible = refs.slice(0, 3);
  const overflow = refs.slice(3);

  useEffect(() => {
    if (!popoverOpen) return;
    const handler = (e: MouseEvent) => {
      if (
        moreRef.current && !moreRef.current.contains(e.target as Node) &&
        popoverRef.current && !popoverRef.current.contains(e.target as Node)
      ) {
        setPopoverOpen(false);
      }
    };
    document.addEventListener('click', handler);
    return () => document.removeEventListener('click', handler);
  }, [popoverOpen]);

  return (
    <div className={`flex flex-wrap gap-2 relative${className ? ' ' + className : ''}`}>
      {visible.map((r) => (
        <Chip key={`${r.launchId}-${r.jurisdictionCode}`} graphRef={r} onOpen={onOpen} />
      ))}
      {overflow.length > 0 && (
        <div style={{ position: 'relative' }}>
          <button
            ref={moreRef}
            style={popoverOpen ? chipHover : chipBase}
            onClick={(e) => { e.stopPropagation(); setPopoverOpen((o) => !o); }}
          >
            +{overflow.length} more
          </button>
          {popoverOpen && (
            <div
              ref={popoverRef}
              style={{
                position: 'absolute',
                top: 'calc(100% + 6px)',
                left: 0,
                zIndex: 50,
                background: '#141414',
                border: '1px solid rgba(255,255,255,0.1)',
                borderRadius: '10px',
                padding: '8px',
                display: 'flex',
                flexDirection: 'column',
                gap: '6px',
                maxHeight: '200px',
                overflowY: 'auto',
                minWidth: '260px',
              }}
            >
              {overflow.map((r) => (
                <Chip
                  key={`${r.launchId}-${r.jurisdictionCode}`}
                  graphRef={r}
                  onOpen={(ref) => { onOpen?.(ref); setPopoverOpen(false); }}
                />
              ))}
            </div>
          )}
        </div>
      )}
    </div>
  );
}
