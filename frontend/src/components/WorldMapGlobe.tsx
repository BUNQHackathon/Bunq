import React, { useEffect, useRef } from 'react';
import Globe from 'globe.gl';
import { MeshLambertMaterial, Color, type Shader } from 'three';

// ── GeoJSON source URLs (same as JurisdictionsPage) ──────────────────────────
const GEO_URL_PRIMARY =
  'https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson/ne_110m_admin_0_countries.geojson';
const GEO_URL_FALLBACK =
  'https://cdn.jsdelivr.net/gh/vasturiano/globe.gl@master/example/datasets/ne_110m_admin_0_countries.geojson';

const INACTIVE_FILL = '#1E1E1E';

const AMBER_HEX = '#b87538';
const STRIPE_GOLD = '#cfb275';
const STRIPE_RED = '#a83820';

const UNKNOWN_HEX = '#444444';
const UNKNOWN_STRIPE_DARK = '#6b6b6b';
const UNKNOWN_STRIPE_LIGHT = '#9a9a9a';

// Stripe band width in screen-space pixels. 6px on each band → 12px cycle,
// large enough to read on small countries when zoomed out, small enough not
// to dominate big countries when zoomed in.
const STRIPE_BAND_PX = 6.0;

// Patch a MeshLambertMaterial so its fragment shader paints diagonal stripes
// based on screen coordinates (gl_FragCoord). This makes stripe width uniform
// across all polygons regardless of size, and the chosen RGB stays true to
// the design (Lambert lighting only modulates intensity, not hue).
function buildAmberStripeMaterial(): MeshLambertMaterial {
  const mat = new MeshLambertMaterial({ color: 0xffffff });
  mat.onBeforeCompile = (shader: Shader) => {
    shader.uniforms.stripeColorA = { value: new Color(STRIPE_GOLD) };
    shader.uniforms.stripeColorB = { value: new Color(STRIPE_RED) };
    shader.uniforms.stripeBandPx = { value: STRIPE_BAND_PX };
    shader.fragmentShader = shader.fragmentShader.replace(
      'void main() {',
      `
      uniform vec3 stripeColorA;
      uniform vec3 stripeColorB;
      uniform float stripeBandPx;
      void main() {
      `,
    );
    // Override the diffuse color right after it's initialized from the
    // material's `diffuse` uniform but before lighting is applied.
    shader.fragmentShader = shader.fragmentShader.replace(
      'vec4 diffuseColor = vec4( diffuse, opacity );',
      `
      float stripeCoord = gl_FragCoord.x + gl_FragCoord.y;
      float stripeBand = mod(floor(stripeCoord / stripeBandPx), 2.0);
      vec3 stripeRgb = stripeBand < 0.5 ? stripeColorA : stripeColorB;
      vec4 diffuseColor = vec4( stripeRgb, opacity );
      `,
    );
  };
  // Distinct customProgramCacheKey so three.js doesn't dedupe this with the
  // unpatched Lambert program used by solid-color materials.
  mat.customProgramCacheKey = () => 'amber-stripes-v1';
  return mat;
}

let _amberMaterial: MeshLambertMaterial | null = null;
function getAmberMaterial(): MeshLambertMaterial {
  if (_amberMaterial) return _amberMaterial;
  _amberMaterial = buildAmberStripeMaterial();
  return _amberMaterial;
}

function buildUnknownStripeMaterial(): MeshLambertMaterial {
  const mat = new MeshLambertMaterial({ color: 0xffffff });
  mat.onBeforeCompile = (shader: Shader) => {
    shader.uniforms.stripeColorA = { value: new Color(UNKNOWN_STRIPE_DARK) };
    shader.uniforms.stripeColorB = { value: new Color(UNKNOWN_STRIPE_LIGHT) };
    shader.uniforms.stripeBandPx = { value: STRIPE_BAND_PX };
    shader.fragmentShader = shader.fragmentShader.replace(
      'void main() {',
      `
      uniform vec3 stripeColorA;
      uniform vec3 stripeColorB;
      uniform float stripeBandPx;
      void main() {
      `,
    );
    shader.fragmentShader = shader.fragmentShader.replace(
      'vec4 diffuseColor = vec4( diffuse, opacity );',
      `
      float stripeCoord = gl_FragCoord.x + gl_FragCoord.y;
      float stripeBand = mod(floor(stripeCoord / stripeBandPx), 2.0);
      vec3 stripeRgb = stripeBand < 0.5 ? stripeColorA : stripeColorB;
      vec4 diffuseColor = vec4( stripeRgb, opacity );
      `,
    );
  };
  mat.customProgramCacheKey = () => 'unknown-stripes-v1';
  return mat;
}

let _unknownMaterial: MeshLambertMaterial | null = null;
function getUnknownMaterial(): MeshLambertMaterial {
  if (_unknownMaterial) return _unknownMaterial;
  _unknownMaterial = buildUnknownStripeMaterial();
  return _unknownMaterial;
}

const _solidMaterials = new Map<string, MeshLambertMaterial>();
function getSolidMaterial(color: string): MeshLambertMaterial {
  const cached = _solidMaterials.get(color);
  if (cached) return cached;
  const mat = new MeshLambertMaterial({ color: new Color(color) });
  _solidMaterials.set(color, mat);
  return mat;
}

function resolveCapMaterial(color: string | undefined): MeshLambertMaterial {
  if (!color) return getSolidMaterial(INACTIVE_FILL);
  if (color === AMBER_HEX) return getAmberMaterial();
  if (color === UNKNOWN_HEX) return getUnknownMaterial();
  return getSolidMaterial(color);
}

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
  // Order matters: pause RAF, then release GPU context BEFORE _destructor
  // disposes the renderer — forceContextLoss is a no-op on a disposed renderer
  // and without it the WebGL context leaks (browsers cap at 8-16).
  try { (instance as any).pauseAnimation?.(); } catch { /* best-effort */ }
  try {
    const r = (instance as unknown as {
      renderer?: () => { forceContextLoss: () => void };
    }).renderer?.();
    r?.forceContextLoss?.();
  } catch { /* best-effort */ }
  try { (instance as any)._destructor?.(); } catch { /* best-effort */ }
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
  selected,
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
  const canvasListenersRef = useRef<{ canvas: HTMLCanvasElement; onLost: (e: Event) => void; onRestored: () => void } | null>(null);
  const featuresRef = useRef<GeoFeature[]>([]);

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
        .globeMaterial(new MeshLambertMaterial({ color: 0x0a0a14 }) as never)
        .polygonsData(features as unknown as object[])
        .polygonCapMaterial(((feat: object) => {
          const iso = readIso3((feat as GeoFeature).properties);
          return resolveCapMaterial(dataRef.current.get(iso)?.color);
        }) as never)
        .polygonSideColor((() => 'rgba(0,0,0,0.3)') as never)
        .polygonStrokeColor((() => 'rgba(255,255,255,0.55)') as never)
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
        .pointOfView({ lat: 48, lng: 5.3, altitude: 1.8 });

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
        const onLost = (e: Event) => { e.preventDefault(); };
        const onRestored = () => {
          // guard against post-unmount context restore resurrecting the globe
          if (!globeInitRef.current || cancelled) return;
          globeInitRef.current = false;
          disposeGlobe(globeRef.current);
          globeRef.current = null;
          initGlobe(features);
        };
        canvas.addEventListener('webglcontextlost', onLost, false);
        canvas.addEventListener('webglcontextrestored', onRestored, false);
        canvasListenersRef.current = { canvas, onLost, onRestored };
      }
    }

    fetchFeatures().then((features) => {
      if (cancelled || !features.length) return;
      featuresRef.current = features;
      initGlobe(features);
    });

    return () => {
      cancelled = true;
      resizeObsRef.current?.disconnect();
      resizeObsRef.current = null;
      if (canvasListenersRef.current) {
        const { canvas, onLost, onRestored } = canvasListenersRef.current;
        canvas.removeEventListener('webglcontextlost', onLost, false);
        canvas.removeEventListener('webglcontextrestored', onRestored, false);
        canvasListenersRef.current = null;
      }
      disposeGlobe(globeRef.current);
      globeRef.current = null;
      globeInitRef.current = false;
      if (containerRef.current) {
        while (containerRef.current.firstChild) containerRef.current.removeChild(containerRef.current.firstChild);
      }
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Pan camera when `selected` changes externally (e.g. from the search input).
  useEffect(() => {
    const globe = globeRef.current;
    if (!globe || !selected) return;
    const feat = featuresRef.current.find((f) => readIso3(f.properties) === selected);
    if (!feat || !feat.bbox) return;
    const lat = (feat.bbox[1] + feat.bbox[3]) / 2;
    const lng = (feat.bbox[0] + feat.bbox[2]) / 2;
    (globe as unknown as {
      pointOfView: (p: { lat: number; lng: number; altitude: number }, ms?: number) => void;
    }).pointOfView({ lat, lng, altitude: 1.4 }, 700);
    const controls = (globe as unknown as {
      controls?: () => { autoRotate: boolean };
    }).controls?.();
    if (controls) controls.autoRotate = false;
  }, [selected]);

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
