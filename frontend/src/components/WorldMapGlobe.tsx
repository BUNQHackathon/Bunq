import React, { useEffect, useRef } from 'react';
import Globe from 'globe.gl';
import { MeshPhongMaterial } from 'three';

// ── GeoJSON source URLs (same as JurisdictionsPage) ──────────────────────────
const GEO_URL_PRIMARY =
  'https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson/ne_110m_admin_0_countries.geojson';
const GEO_URL_FALLBACK =
  'https://cdn.jsdelivr.net/gh/vasturiano/globe.gl@master/example/datasets/ne_110m_admin_0_countries.geojson';

const INACTIVE_FILL = '#1E1E1E';

// ── Types ─────────────────────────────────────────────────────────────────────

type GeoFeature = {
  type: string;
  properties: Record<string, unknown>;
  geometry: unknown;
  bbox?: [number, number, number, number];
};

export interface WorldMapGlobeProps {
  /** ISO-3 country code → fill color and optional label */
  data: Map<string, { color: string; label?: string }>;
  /** ISO-3 codes that are visually emphasized. If omitted, all data keys are treated as demo countries. */
  demoCountries?: Set<string>;
  /** Currently selected ISO-3 (for highlight ring) */
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

function disposeGlobe(instance: InstanceType<typeof Globe> | null): void {
  if (!instance) return;
  try {
    const r = (instance as unknown as {
      renderer?: () => { dispose: () => void; forceContextLoss: () => void };
    }).renderer?.();
    if (r) { r.forceContextLoss(); r.dispose(); }
    const scene = (instance as unknown as {
      scene?: () => {
        traverse: (cb: (obj: {
          geometry?: { dispose?: () => void };
          material?: { dispose?: () => void } | Array<{ dispose?: () => void }>;
        }) => void) => void;
      };
    }).scene?.();
    scene?.traverse((obj) => {
      if (obj.geometry?.dispose) obj.geometry.dispose();
      if (Array.isArray(obj.material)) obj.material.forEach((m) => m.dispose?.());
      else if (obj.material?.dispose) obj.material.dispose();
    });
  } catch { /* best-effort */ }
}

// ── Component ─────────────────────────────────────────────────────────────────

function useResponsiveHeight(defaultHeight: number): number {
  const [h, setH] = React.useState(() =>
    typeof window !== 'undefined' && window.innerWidth < 640
      ? Math.min(360, window.innerHeight * 0.55)
      : defaultHeight,
  );
  React.useEffect(() => {
    function onResize() {
      setH(window.innerWidth < 640 ? Math.min(360, window.innerHeight * 0.55) : defaultHeight);
    }
    window.addEventListener('resize', onResize);
    return () => window.removeEventListener('resize', onResize);
  }, [defaultHeight]);
  return h;
}

export default function WorldMapGlobe({
  data,
  onSelect,
  onHover,
  height,
}: WorldMapGlobeProps) {
  const computedHeight = useResponsiveHeight(520);
  const effectiveHeight = height ?? computedHeight;
  const containerRef = useRef<HTMLDivElement>(null);
  const globeRef = useRef<InstanceType<typeof Globe> | null>(null);
  const globeInitRef = useRef(false);
  const hoveredRef = useRef<GeoFeature | null>(null);
  const resizeObsRef = useRef<ResizeObserver | null>(null);

  // Stable callback refs — no re-init needed when callbacks change
  const onSelectRef = useRef(onSelect);
  const onHoverRef = useRef(onHover);
  const dataRef = useRef(data);
  onSelectRef.current = onSelect;
  onHoverRef.current = onHover;
  dataRef.current = data;

  useEffect(() => {
    let cancelled = false;

    async function fetchFeatures(): Promise<GeoFeature[]> {
      for (const url of [GEO_URL_PRIMARY, GEO_URL_FALLBACK]) {
        try {
          const res = await fetch(url);
          if (!res.ok) continue;
          const json = await res.json() as { features: GeoFeature[] };
          return json.features;
        } catch { /* try next */ }
      }
      return [];
    }

    function initGlobe(features: GeoFeature[]) {
      if (globeInitRef.current || !containerRef.current) return;
      const el = containerRef.current;
      globeInitRef.current = true;

      const w = el.clientWidth || el.getBoundingClientRect().width || 800;
      const h = el.clientHeight || el.getBoundingClientRect().height || effectiveHeight;

      const globe = new Globe(el);

      globe
        .width(w)
        .height(h)
        .backgroundColor('#080808')
        .showAtmosphere(true)
        .atmosphereColor('#FF7819')
        .atmosphereAltitude(0.14)
        .showGlobe(true)
        .globeMaterial(new MeshPhongMaterial({ color: 0x0a0a14 }) as never)
        .polygonsData(features as unknown as object[])
        .polygonCapColor(((feat: object) => {
          const iso = readIso3((feat as GeoFeature).properties);
          return dataRef.current.get(iso)?.color ?? INACTIVE_FILL;
        }) as never)
        .polygonSideColor((() => 'rgba(0,0,0,0.3)') as never)
        .polygonStrokeColor((() => 'rgba(255,255,255,0.15)') as never)
        .polygonAltitude(((feat: object) =>
          feat === hoveredRef.current ? 0.025 : 0.008
        ) as never)
        .polygonLabel(((feat: object) => {
          const f = feat as GeoFeature;
          const iso = readIso3(f.properties);
          const entry = dataRef.current.get(iso);
          const admin =
            (f.properties.ADMIN as string | undefined) ??
            (f.properties.admin as string | undefined) ??
            iso;
          const label = entry?.label ?? admin;
          return `<div style="background:#1C1C1C;color:#fff;padding:5px 12px;border-radius:999px;font-size:12px;font-family:Inter,sans-serif;font-weight:500">${label}</div>`;
        }) as never)
        .onPolygonHover(((feat: object | null) => {
          hoveredRef.current = (feat as GeoFeature | null) ?? null;
          globe.polygonAltitude(((f: object) =>
            f === hoveredRef.current ? 0.025 : 0.008
          ) as never);
          if (el) el.style.cursor = feat ? 'pointer' : 'default';
          const iso = feat ? readIso3((feat as GeoFeature).properties) : null;
          onHoverRef.current?.(iso);
        }) as never)
        .onPolygonClick(((feat: object) => {
          if (!feat) return;
          const f = feat as GeoFeature;
          const iso = readIso3(f.properties);
          onSelectRef.current?.(iso);
          if (f.bbox) {
            const lat = (f.bbox[1] + f.bbox[3]) / 2;
            const lng = (f.bbox[0] + f.bbox[2]) / 2;
            globe.pointOfView({ lat, lng, altitude: 1.4 }, 700);
          }
        }) as never)
        .pointOfView({ lat: 48, lng: 10, altitude: 1.8 });

      globeRef.current = globe;

      // Auto-rotate until user interacts
      const controls = (globe as unknown as {
        controls?: () => { autoRotate: boolean; autoRotateSpeed: number };
      }).controls?.();
      if (controls) { controls.autoRotate = true; controls.autoRotateSpeed = 0.35; }

      // Resize observer
      const ro = new ResizeObserver(() => {
        const nw = el.clientWidth, nh = el.clientHeight;
        if (nw > 0 && nh > 0) globe.width(nw).height(nh);
      });
      ro.observe(el);
      resizeObsRef.current = ro;

      // WebGL context loss/restore
      const rendererObj = (globe as unknown as {
        renderer?: () => { domElement: HTMLCanvasElement };
      }).renderer?.();
      const canvas = rendererObj?.domElement;
      if (canvas) {
        canvas.addEventListener('webglcontextlost', (e) => { e.preventDefault(); }, false);
        canvas.addEventListener('webglcontextrestored', () => {
          globeInitRef.current = false;
          disposeGlobe(globeRef.current);
          globeRef.current = null;
          initGlobe(features);
        }, false);
      }
    }

    fetchFeatures().then((features) => {
      if (cancelled || !features.length) return;
      initGlobe(features);
    });

    return () => {
      cancelled = true;
      resizeObsRef.current?.disconnect();
      resizeObsRef.current = null;
      disposeGlobe(globeRef.current);
      globeRef.current = null;
      globeInitRef.current = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <div
      ref={containerRef}
      style={{
        width: '100%',
        height: '100%',
        minHeight: effectiveHeight,
        background: '#080808',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
      }}
    />
  );
}
