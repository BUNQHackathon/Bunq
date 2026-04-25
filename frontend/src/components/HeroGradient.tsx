import { useId } from 'react';

interface HeroGradientProps {
  colors?: [string, string, string];
  sinks?: [number, number, number];
  spread?: number;
  blur?: number;
  grainOpacity?: number;
  /** Play a one-shot scaleY-from-bottom reveal on mount. Each orb rises at its own pace. */
  animate?: boolean;
  className?: string;
  style?: React.CSSProperties;
}

const REVEAL_STYLE_ID = 'hero-gradient-reveal-style';
if (typeof document !== 'undefined' && !document.getElementById(REVEAL_STYLE_ID)) {
  const s = document.createElement('style');
  s.id = REVEAL_STYLE_ID;
  s.textContent = `@keyframes heroGradientReveal { from { transform: scaleY(0); } to { transform: scaleY(1); } }`;
  document.head.appendChild(s);
}

// Reversed: amber (outer) rises first and quick, red core arrives last and slow.
const ORB_ANIMATIONS: [string, string, string] = [
  'heroGradientReveal 1400ms 180ms cubic-bezier(0.16, 1, 0.3, 1) both',
  'heroGradientReveal 1100ms 80ms cubic-bezier(0.22, 0.61, 0.36, 1) both',
  'heroGradientReveal 850ms cubic-bezier(0.34, 1.18, 0.64, 1) both',
];

export default function HeroGradient({
  colors = ['#eb2700', '#C86334', '#d9a67d'],
  sinks = [65, 85, 92],
  spread = 113,
  blur = 25,
  grainOpacity = 0.25,
  animate = false,
  className,
  style,
}: HeroGradientProps) {
  // useId can include colons in its output which are invalid in SVG filter id attributes
  const rawId = useId().replace(/:/g, '');
  const filterId = `grain-${rawId}`;
  const [c1, c2, c3] = colors;
  const [s1, s2, s3] = sinks;
  const [a1, a2, a3] = ORB_ANIMATIONS;

  return (
    <div
      aria-hidden="true"
      className={className}
      style={{ position: 'absolute', inset: 0, overflow: 'hidden', pointerEvents: 'none', ...style }}
    >
      {/* Orb 1 — red core */}
      <div style={{ position: 'absolute', width: `${spread}%`, aspectRatio: '1994 / 717', left: '50%', bottom: 0, transform: `translate(-50%, ${s1}%)` }}>
        <div style={{ width: '100%', height: '100%', borderRadius: '50%', background: `radial-gradient(ellipse farthest-side at center, ${c1} 29%, #292928 100%)`, filter: `blur(${blur}px)`, transformOrigin: 'bottom', animation: animate ? a1 : undefined }} />
      </div>

      {/* Orb 2 — orange middle */}
      <div style={{ position: 'absolute', width: `${spread}%`, aspectRatio: '1994 / 717', left: '52%', bottom: 0, transform: `translate(-50%, ${s2}%)` }}>
        <div style={{ width: '100%', height: '100%', borderRadius: '50%', background: c2, filter: `blur(${blur}px)`, opacity: 0.95, transformOrigin: 'bottom', animation: animate ? a2 : undefined }} />
      </div>

      {/* Orb 3 — amber outer */}
      <div style={{ position: 'absolute', width: `${spread + 20}%`, aspectRatio: '2222 / 717', left: '54%', bottom: 0, transform: `translate(-50%, ${s3}%)` }}>
        <div style={{ width: '100%', height: '100%', borderRadius: '50%', background: c3, filter: `blur(${blur + 4}px)`, opacity: 0.9, transformOrigin: 'bottom', animation: animate ? a3 : undefined }} />
      </div>

      <svg
        aria-hidden="true"
        style={{ position: 'absolute', inset: 0, width: '100%', height: '100%', pointerEvents: 'none', mixBlendMode: 'soft-light' }}
      >
        <defs>
          <filter id={filterId}>
            <feTurbulence type="turbulence" baseFrequency="0.65" numOctaves={3} stitchTiles="stitch" />
            <feColorMatrix type="saturate" values="0" />
            <feComponentTransfer>
              <feFuncR type="linear" slope={4} intercept={-1.5} />
              <feFuncG type="linear" slope={4} intercept={-1.5} />
              <feFuncB type="linear" slope={4} intercept={-1.5} />
            </feComponentTransfer>
          </filter>
        </defs>
        <rect width="100%" height="100%" filter={`url(#${filterId})`} opacity={grainOpacity} />
      </svg>
    </div>
  );
}
