import { useId } from 'react';

interface HeroGradientProps {
  colors?: [string, string, string];
  sinks?: [number, number, number];
  spread?: number;
  blur?: number;
  grainOpacity?: number;
  className?: string;
  style?: React.CSSProperties;
}

export default function HeroGradient({
  colors = ['#eb2700', '#C86334', '#d9a67d'],
  sinks = [65, 85, 92],
  spread = 113,
  blur = 25,
  grainOpacity = 0.25,
  className,
  style,
}: HeroGradientProps) {
  // useId can include colons in its output which are invalid in SVG filter id attributes
  const rawId = useId().replace(/:/g, '');
  const filterId = `grain-${rawId}`;
  const [c1, c2, c3] = colors;
  const [s1, s2, s3] = sinks;

  return (
    <div
      aria-hidden="true"
      className={className}
      style={{ position: 'absolute', inset: 0, overflow: 'hidden', pointerEvents: 'none', ...style }}
    >
      <div style={{ position: 'absolute', width: `${spread}%`, aspectRatio: '1994 / 717', left: '50%', bottom: 0, transform: `translate(-50%, ${s1}%)`, borderRadius: '50%', background: `radial-gradient(ellipse farthest-side at center, ${c1} 29%, #292928 100%)`, filter: `blur(${blur}px)` }} />
      <div style={{ position: 'absolute', width: `${spread}%`, aspectRatio: '1994 / 717', left: '52%', bottom: 0, transform: `translate(-50%, ${s2}%)`, borderRadius: '50%', background: c2, filter: `blur(${blur}px)`, opacity: 0.95 }} />
      <div style={{ position: 'absolute', width: `${spread + 20}%`, aspectRatio: '2222 / 717', left: '54%', bottom: 0, transform: `translate(-50%, ${s3}%)`, borderRadius: '50%', background: c3, filter: `blur(${blur + 4}px)`, opacity: 0.9 }} />
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
