import { useEffect, useRef, useState } from 'react';
import * as d3 from 'd3';

// ── GeoJSON source URLs (same as JurisdictionsPage) ──────────────────────────
const GEO_URL_PRIMARY =
  'https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson/ne_110m_admin_0_countries.geojson';
const GEO_URL_FALLBACK =
  'https://cdn.jsdelivr.net/gh/vasturiano/globe.gl@master/example/datasets/ne_110m_admin_0_countries.geojson';

const INACTIVE_FILL = '#1E1E1E';

// ── Types ─────────────────────────────────────────────────────────────────────

type GeoFeature = d3.GeoPermissibleObjects & {
  properties: Record<string, unknown> & { ADMIN?: string; admin?: string };
  bbox?: [number, number, number, number];
};

export interface WorldMapD3Props {
  /** ISO-3 country code → fill color and optional label */
  data: Map<string, { color: string; label?: string }>;
  /** ISO-3 codes that are visually emphasized. If omitted, all data keys are treated as demo countries. */
  demoCountries?: Set<string>;
  /** Currently selected ISO-3 (gets a brighter stroke) */
  selected?: string;
  /** Called when user clicks a country */
  onSelect?: (iso3: string) => void;
  /** Fires on hover with ISO-3, or null on leave */
  onHover?: (iso3: string | null) => void;
  /** Height in px, default 520 */
  height?: number;
}

// ── Helpers ───────────────────────────────────────────────────────────────────

function readIso3(props: Record<string, unknown>): string {
  const candidates = ['ISO_A3', 'iso_a3', 'ISO_A3_EH', 'ADM0_A3', 'adm0_a3', 'iso_a3_eh'];
  for (const k of candidates) {
    const v = props[k];
    if (typeof v === 'string' && v.length === 3 && v !== '-99' && v !== '-999') return v;
  }
  return '';
}

function brighten(hex: string): string {
  const r = parseInt(hex.slice(1, 3), 16);
  const g = parseInt(hex.slice(3, 5), 16);
  const b = parseInt(hex.slice(5, 7), 16);
  return `rgb(${Math.min(255, r + 50)},${Math.min(255, g + 40)},${Math.min(255, b + 30)})`;
}

// ── Component ─────────────────────────────────────────────────────────────────

export default function WorldMapD3({
  data,
  selected,
  onSelect,
  onHover,
}: WorldMapD3Props) {
  const containerRef = useRef<HTMLDivElement>(null);
  const svgRef = useRef<SVGSVGElement>(null);
  const tooltipRef = useRef<HTMLDivElement>(null);

  const [dims, setDims] = useState<{ w: number; h: number } | null>(null);
  const featuresRef = useRef<GeoFeature[]>([]);

  // Keep stable refs to callbacks so effects don't re-run on every render
  const onSelectRef = useRef(onSelect);
  const onHoverRef = useRef(onHover);
  const dataRef = useRef(data);
  const selectedRef = useRef(selected);
  onSelectRef.current = onSelect;
  onHoverRef.current = onHover;
  dataRef.current = data;
  selectedRef.current = selected;

  // Stable refs for d3 objects
  const mapSelRef = useRef<d3.Selection<SVGPathElement, GeoFeature, SVGGElement, unknown> | null>(null);
  const zoomRef = useRef<d3.ZoomBehavior<SVGSVGElement, unknown> | null>(null);
  const pathGenRef = useRef<d3.GeoPath | null>(null);

  // ResizeObserver: update dims when container size changes
  useEffect(() => {
    const el = containerRef.current;
    if (!el) return;
    const ro = new ResizeObserver((entries) => {
      const { width, height: h } = entries[0].contentRect;
      if (width > 0 && h > 0) setDims({ w: width, h });
    });
    ro.observe(el);
    const r = el.getBoundingClientRect();
    if (r.width > 0 && r.height > 0) setDims({ w: r.width, h: r.height });
    return () => ro.disconnect();
  }, []);

  // Sync selection stroke whenever `selected` prop changes
  useEffect(() => {
    if (!mapSelRef.current) return;
    mapSelRef.current
      .attr('stroke', (d: GeoFeature) =>
        readIso3(d.properties) === selected ? '#FF7819' : 'rgba(255,255,255,0.1)',
      )
      .attr('stroke-width', (d: GeoFeature) =>
        readIso3(d.properties) === selected ? 1.5 : 0.4,
      );
  }, [selected]);

  // Sync fill colors whenever `data` changes (e.g. when launch detail loads
  // after the map was already initialised).
  useEffect(() => {
    if (!mapSelRef.current) return;
    mapSelRef.current.attr('fill', (d: GeoFeature) =>
      data.get(readIso3(d.properties))?.color ?? INACTIVE_FILL,
    );
  }, [data]);

  // Pan/zoom map to the selected country (e.g. when picked from search)
  useEffect(() => {
    const svgEl = svgRef.current;
    const path = pathGenRef.current;
    const zoom = zoomRef.current;
    const containerEl = containerRef.current;
    if (!svgEl || !path || !zoom || !containerEl || !selected) return;
    const feat = featuresRef.current.find(
      (f) => readIso3(f.properties) === selected,
    );
    if (!feat) return;
    const centroid = path.centroid(feat);
    if (!centroid || Number.isNaN(centroid[0]) || Number.isNaN(centroid[1])) return;
    const rect = containerEl.getBoundingClientRect();
    const W = rect.width;
    const H = rect.height;
    if (W <= 0 || H <= 0) return;
    const scale = 2.5;
    const tx = W / 2 - centroid[0] * scale;
    const ty = H / 2 - centroid[1] * scale;
    d3.select(svgEl)
      .transition()
      .duration(700)
      .call(
        zoom.transform as never,
        d3.zoomIdentity.translate(tx, ty).scale(scale),
      );
  }, [selected]);

  // Main map initialisation — reruns when dims change
  useEffect(() => {
    if (!dims) return;
    let cancelled = false;

    async function fetchFeatures(): Promise<GeoFeature[]> {
      if (featuresRef.current.length) return featuresRef.current;
      for (const url of [GEO_URL_PRIMARY, GEO_URL_FALLBACK]) {
        try {
          const res = await fetch(url);
          if (!res.ok) continue;
          const json = await res.json() as { features: GeoFeature[] };
          featuresRef.current = json.features;
          return json.features;
        } catch { /* try next */ }
      }
      return [];
    }

    function initMap(features: GeoFeature[]) {
      const svgEl = svgRef.current;
      const containerEl = containerRef.current;
      if (!svgEl || !containerEl) return;

      if (!dims) return;
      const W = dims.w;
      const H = dims.h;

      // Natural-earth1 world is ~5 units wide, ~2.6 tall at scale 1.
      // Scale by max so we fill the limiting dimension, then zoom in for Europe focus.
      const projScale = Math.max(W / 5.0, H / 2.6) * 1.3;
      const proj = d3.geoNaturalEarth1().scale(projScale).translate([W / 2, H / 2]).center([5.3, 35]);
      const path = d3.geoPath().projection(proj);
      pathGenRef.current = path;

      const svg = d3.select(svgEl);
      svg.select('#wmd3-graticule-g').selectAll('*').remove();
      svg.select('#wmd3-map-g').selectAll('*').remove();

      // Track pointer-down position to distinguish click from drag/zoom
      let downX = 0;
      let downY = 0;
      let downIso = '';
      let downSvgTargetId = '';

      // Graticule
      const grat = d3.geoGraticule()();
      svg.select<SVGGElement>('#wmd3-graticule-g')
        .append('path')
        .datum(grat)
        .attr('d', path as unknown as string)
        .attr('fill', 'none')
        .attr('stroke', 'rgba(255,255,255,0.04)')
        .attr('stroke-width', 0.4);

      // Zoom
      const zoom = d3.zoom<SVGSVGElement, unknown>()
        .scaleExtent([0.5, 10])
        .on('zoom', (e) => {
          d3.select(svgEl).select<SVGGElement>('#wmd3-map-g').attr('transform', e.transform);
        });
      zoomRef.current = zoom;
      svg.call(zoom);

      // Country paths
      const mapSel = svg.select<SVGGElement>('#wmd3-map-g')
        .selectAll<SVGPathElement, GeoFeature>('path')
        .data(features)
        .join('path')
        .attr('d', (d) => path(d) ?? '')
        .attr('fill', (d) => dataRef.current.get(readIso3(d.properties))?.color ?? INACTIVE_FILL)
        .attr('stroke', (d) =>
          readIso3(d.properties) === selectedRef.current ? '#FF7819' : 'rgba(255,255,255,0.1)',
        )
        .attr('stroke-width', (d) =>
          readIso3(d.properties) === selectedRef.current ? 1.5 : 0.4,
        )
        .style('cursor', 'pointer')
        .on('mouseenter', function (e: MouseEvent, d: GeoFeature) {
          const iso = readIso3(d.properties);
          const fill = dataRef.current.get(iso)?.color ?? INACTIVE_FILL;
          d3.select(this)
            .attr('stroke', '#FF7819')
            .attr('stroke-width', 1.2)
            .attr('fill', brighten(fill));
          onHoverRef.current?.(iso);

          const tip = tooltipRef.current;
          if (tip) {
            const entry = dataRef.current.get(iso);
            const name = (d.properties.ADMIN as string | undefined)
              ?? (d.properties.admin as string | undefined)
              ?? iso;
            const label = entry?.label ?? name;
            const tn = tip.querySelector<HTMLDivElement>('.tn');
            if (tn) tn.textContent = label;
            tip.style.opacity = '1';
            moveTip(e);
          }
        })
        .on('mousemove', function (_e: MouseEvent) { moveTip(_e); })
        .on('mouseleave', function (_e: MouseEvent, d: GeoFeature) {
          const iso = readIso3(d.properties);
          const isSelected = iso === selectedRef.current;
          d3.select(this)
            .attr('stroke', isSelected ? '#FF7819' : 'rgba(255,255,255,0.1)')
            .attr('stroke-width', isSelected ? 1.5 : 0.4)
            .attr('fill', dataRef.current.get(iso)?.color ?? INACTIVE_FILL);
          const tip = tooltipRef.current;
          if (tip) tip.style.opacity = '0';
          onHoverRef.current?.(null);
        })
        .on('pointerdown', function (e: PointerEvent, d: GeoFeature) {
          downX = e.clientX;
          downY = e.clientY;
          downIso = readIso3(d.properties);
        })
        .on('pointerup', function (e: PointerEvent, d: GeoFeature) {
          const iso = readIso3(d.properties);
          const dx = e.clientX - downX;
          const dy = e.clientY - downY;
          if (dx * dx + dy * dy >= 16) return;
          if (downIso !== iso) return;
          onSelectRef.current?.(iso);
          mapSelRef.current
            ?.attr('stroke', 'rgba(255,255,255,0.1)')
            .attr('stroke-width', 0.4);
          d3.select(this).attr('stroke', '#FF7819').attr('stroke-width', 1.5);
        });

      mapSelRef.current = mapSel as unknown as d3.Selection<SVGPathElement, GeoFeature, SVGGElement, unknown>;

      // Pointer on ocean/empty svg deselects (use pointerdown/up + movement threshold
      // so zoom/pan drags don't trigger deselect, and so d3-zoom's preventDefault
      // on mousedown can't swallow the interaction).
      svg.on('pointerdown.deselect', (e: PointerEvent) => {
        const target = e.target as Element;
        downSvgTargetId = target.id || '';
        downX = e.clientX;
        downY = e.clientY;
      });
      svg.on('pointerup.deselect', (e: PointerEvent) => {
        const target = e.target as Element;
        const id = target.id || '';
        const dx = e.clientX - downX;
        const dy = e.clientY - downY;
        if (dx * dx + dy * dy >= 16) return;
        if (
          (id === 'wmd3-svg' || id === 'wmd3-ocean-bg') &&
          (downSvgTargetId === 'wmd3-svg' || downSvgTargetId === 'wmd3-ocean-bg')
        ) {
          onSelectRef.current?.('');
        }
      });
    }

    function moveTip(e: MouseEvent) {
      const tip = tooltipRef.current;
      const container = containerRef.current;
      if (!tip || !container) return;
      const r = container.getBoundingClientRect();
      tip.style.left = `${e.clientX - r.left + 14}px`;
      tip.style.top = `${e.clientY - r.top - 12}px`;
    }

    fetchFeatures().then((features) => {
      if (cancelled || !features.length) return;
      initMap(features);
    });

    return () => {
      cancelled = true;
      // Clean up zoom listener
      if (svgRef.current) {
        const svg = d3.select(svgRef.current);
        svg.on('.zoom', null);
        svg.on('pointerdown.deselect', null);
        svg.on('pointerup.deselect', null);
      }
      mapSelRef.current = null;
      zoomRef.current = null;
      pathGenRef.current = null;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [dims]);

  return (
    <div ref={containerRef} style={{ position: 'relative', width: '100%', height: '100%', minHeight: 280 }}>
      <svg
        ref={svgRef}
        id="wmd3-svg"
        style={{ width: '100%', height: '100%', cursor: 'grab' }}
      >
        <defs>
          <radialGradient id="wmd3-ocean-grad" cx="50%" cy="50%" r="50%">
            <stop offset="0%" stopColor="#0A0A14" />
            <stop offset="100%" stopColor="#050508" />
          </radialGradient>
        </defs>
        <rect id="wmd3-ocean-bg" width="100%" height="100%" fill="url(#wmd3-ocean-grad)" />
        <g id="wmd3-graticule-g" />
        <g id="wmd3-map-g" />
      </svg>

      {/* Tooltip */}
      <div
        ref={tooltipRef}
        style={{
          position: 'absolute',
          pointerEvents: 'none',
          zIndex: 50,
          background: '#1C1C1C',
          borderRadius: 999,
          padding: '5px 12px',
          fontSize: 12,
          color: '#fff',
          fontFamily: 'Inter, sans-serif',
          fontWeight: 500,
          opacity: 0,
          transition: 'opacity 100ms',
        }}
      >
        <div className="tn" />
      </div>
    </div>
  );
}
