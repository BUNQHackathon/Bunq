import { useEffect, useRef, useState, useCallback } from 'react';
import { Link } from 'react-router-dom';
import * as d3 from 'd3';
import Globe from 'globe.gl';
import { MeshPhongMaterial } from 'three';

// ── Mock data (inlined from portal.ts) ────────────────────────────────────

type CountryStatus = 'active' | 'watchlist' | 'restricted' | 'inactive';

const statusLabels: Record<CountryStatus, string> = {
  active: 'Active',
  watchlist: 'Watchlist',
  restricted: 'Restricted',
  inactive: 'Inactive',
};

const countryStatus: Record<string, CountryStatus> = {
  NLD: 'active', DEU: 'active', FRA: 'active', ESP: 'active', ITA: 'active', BEL: 'active',
  AUT: 'active', PRT: 'active', IRL: 'active', FIN: 'active', GRC: 'active', LUX: 'active',
  MLT: 'active', CYP: 'active', EST: 'active', LVA: 'active', LTU: 'active', SVK: 'active',
  SVN: 'active', HRV: 'active', NOR: 'active', SWE: 'active', DNK: 'active', ISL: 'active',
  LIE: 'active', CHE: 'active', GBR: 'active', POL: 'active', CZE: 'active', HUN: 'active',
  BGR: 'active', ROU: 'active',
  TUR: 'watchlist', ARE: 'watchlist', SGP: 'watchlist', USA: 'watchlist', CAN: 'watchlist',
  AUS: 'watchlist', BRA: 'watchlist', MEX: 'watchlist', ZAF: 'watchlist', IND: 'watchlist',
  IDN: 'watchlist', JPN: 'watchlist', KOR: 'watchlist', SAU: 'watchlist', QAT: 'watchlist',
  RUS: 'restricted', BLR: 'restricted', PRK: 'restricted', IRN: 'restricted', SYR: 'restricted',
  CUB: 'restricted', VEN: 'restricted', SDN: 'restricted', MMR: 'restricted', LBY: 'restricted',
  YEM: 'restricted', SOM: 'restricted', ZWE: 'restricted', AFG: 'restricted',
};

const countryDetails: Record<string, { name: string; license: string; regulator: string; note: string }> = {
  NLD: { name: 'Netherlands', license: 'Full Banking License', regulator: 'De Nederlandsche Bank (DNB)', note: 'Primary jurisdiction, licensed since 2012' },
  DEU: { name: 'Germany', license: 'EU Passport (DNB)', regulator: 'BaFin', note: 'Active operations since 2018' },
  FRA: { name: 'France', license: 'EU Passport (DNB)', regulator: 'ACPR', note: 'Active operations since 2019' },
  GBR: { name: 'United Kingdom', license: 'E-Money Institution', regulator: 'FCA', note: 'Post-Brexit EMI license' },
  ESP: { name: 'Spain', license: 'EU Passport (DNB)', regulator: 'Banco de España', note: 'Active since 2020' },
  ITA: { name: 'Italy', license: 'EU Passport (DNB)', regulator: "Banca d'Italia", note: 'Active since 2020' },
  USA: { name: 'United States', license: 'Expansion Review', regulator: 'FinCEN / OCC', note: 'Market entry under review' },
  RUS: { name: 'Russia', license: 'Restricted', regulator: 'N/A', note: 'Sanctioned jurisdiction — no operations' },
  BLR: { name: 'Belarus', license: 'Restricted', regulator: 'N/A', note: 'Sanctioned jurisdiction — no operations' },
  IRN: { name: 'Iran', license: 'Restricted', regulator: 'N/A', note: 'Sanctioned jurisdiction — no operations' },
  NOR: { name: 'Norway', license: 'EEA Passport (DNB)', regulator: 'Finanstilsynet', note: 'Active EEA operations' },
  SWE: { name: 'Sweden', license: 'EEA Passport (DNB)', regulator: 'Finansinspektionen', note: 'Active EEA operations' },
  DNK: { name: 'Denmark', license: 'EEA Passport (DNB)', regulator: 'Finanstilsynet', note: 'Active EEA operations' },
  CHE: { name: 'Switzerland', license: 'FINMA Authorized', regulator: 'FINMA', note: 'Non-EU active operations' },
  POL: { name: 'Poland', license: 'EU Passport (DNB)', regulator: 'KNF', note: 'Active operations since 2021' },
  BEL: { name: 'Belgium', license: 'EU Passport (DNB)', regulator: 'NBB', note: 'Active operations' },
  AUT: { name: 'Austria', license: 'EU Passport (DNB)', regulator: 'FMA', note: 'Active operations' },
  CHN: { name: 'China', license: 'Not licensed', regulator: 'PBOC', note: 'No current operations' },
  SAU: { name: 'Saudi Arabia', license: 'Expansion Review', regulator: 'SAMA', note: 'Under evaluation' },
};

const documents = [
  { id: 1, category: 'Terms & Conditions', title: 'General Terms & Conditions v4.2', updated: 'Jan 2026' },
  { id: 2, category: 'Pricing', title: 'Personal Account Fee Schedule Q1 2026', updated: 'Jan 2026' },
  { id: 3, category: 'Privacy', title: 'Privacy Policy & GDPR Statement', updated: 'Dec 2025' },
  { id: 4, category: 'AML', title: 'AML & KYC Policy Framework 2025', updated: 'Nov 2025' },
  { id: 5, category: 'Licensing', title: 'DNB Banking License Certificate', updated: 'Mar 2024' },
  { id: 6, category: 'Reports', title: 'Annual Report 2025 — Full Version', updated: 'Apr 2026' },
  { id: 7, category: 'Terms & Conditions', title: 'Business Account Terms v2.1', updated: 'Dec 2025' },
  { id: 8, category: 'Privacy', title: 'Cookie Policy — Updated Apr 2026', updated: 'Feb 2026' },
  { id: 9, category: 'AML', title: 'Sanctions Screening Procedures Manual', updated: 'Jan 2026' },
];

// ── Palette ────────────────────────────────────────────────────────────────

const STATUS_MAP_FILL: Record<CountryStatus, string> = {
  active: '#FF7819',
  watchlist: '#9B5E1A',
  restricted: '#5C1212',
  inactive: '#1E1E1E',
};

const PILL_STYLE: Record<CountryStatus, { bg: string; color: string }> = {
  active: { bg: 'rgba(255,120,25,0.18)', color: '#FF9F55' },
  watchlist: { bg: 'rgba(200,120,30,0.18)', color: '#C47A2A' },
  restricted: { bg: 'rgba(130,20,20,0.25)', color: '#E05050' },
  inactive: { bg: 'rgba(80,80,80,0.18)', color: '#888' },
};

const CAT_COLOR: Record<string, string> = {
  'Terms & Conditions': '#FF7819',
  AML: '#FF9F55',
  Privacy: '#B08AFF',
  Licensing: '#5ECFA0',
  Reports: '#5ECFA0',
  Pricing: '#FFD080',
};

const STATUS_DOCS_KEYS: Record<CountryStatus, string[]> = {
  active: ['Terms & Conditions', 'Licensing', 'Privacy', 'AML', 'Pricing'],
  watchlist: ['AML', 'Reports'],
  restricted: ['AML'],
  inactive: ['AML'],
};

const EEA_CODES = new Set(['NOR', 'ISL', 'LIE', 'CHE', 'GBR']);

const GEO_URL_PRIMARY =
  'https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson/ne_110m_admin_0_countries.geojson';
// Fallback if the GitHub raw URL is unreachable
const GEO_URL_FALLBACK =
  'https://cdn.jsdelivr.net/gh/vasturiano/globe.gl@master/example/datasets/ne_110m_admin_0_countries.geojson';

// ── Types ──────────────────────────────────────────────────────────────────

type GeoFeature = d3.GeoPermissibleObjects & {
  properties: Record<string, unknown> & { ADMIN?: string; admin?: string };
  bbox?: [number, number, number, number];
};

// ── Helpers ────────────────────────────────────────────────────────────────

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

function getStatus(iso: string): CountryStatus {
  return countryStatus[iso] || 'inactive';
}

function getCountryFill(iso: string): string {
  return STATUS_MAP_FILL[getStatus(iso)];
}

function disposeGlobe(instance: InstanceType<typeof Globe> | null): void {
  if (!instance) return;
  try {
    const r = (instance as unknown as { renderer?: () => { dispose: () => void; forceContextLoss: () => void } }).renderer?.();
    if (r) { r.forceContextLoss(); r.dispose(); }
    const scene = (instance as unknown as { scene?: () => { traverse: (cb: (obj: { geometry?: { dispose?: () => void }; material?: { dispose?: () => void } | Array<{ dispose?: () => void }> }) => void) => void } }).scene?.();
    scene?.traverse((obj) => {
      if (obj.geometry?.dispose) obj.geometry.dispose();
      if (Array.isArray(obj.material)) obj.material.forEach((m) => m.dispose?.());
      else if (obj.material?.dispose) obj.material.dispose();
    });
  } catch { /* best-effort */ }
}

// ── Computed stats ─────────────────────────────────────────────────────────

function computeStats() {
  let active = 0, watchlist = 0, restricted = 0, eea = 0;
  for (const [iso, st] of Object.entries(countryStatus)) {
    if (st === 'active') { active++; if (EEA_CODES.has(iso)) eea++; }
    if (st === 'watchlist') watchlist++;
    if (st === 'restricted') restricted++;
  }
  return { active, watchlist, restricted, eea, activeEU: active - eea };
}

const stats = computeStats();

// ── Component ──────────────────────────────────────────────────────────────

export default function JurisdictionsPage() {
  const [view, setView] = useState<'map' | 'globe'>('globe');
  const [selectedIso, setSelectedIso] = useState<string | null>(null);
  const [searchQuery, setSearchQuery] = useState('');
  const [searchOpen, setSearchOpen] = useState(false);
  const [searchMatches, setSearchMatches] = useState<GeoFeature[]>([]);

  const vizRef = useRef<HTMLDivElement>(null);
  const mapViewRef = useRef<HTMLDivElement>(null);
  const mapSvgRef = useRef<SVGSVGElement>(null);
  const globeViewRef = useRef<HTMLDivElement>(null);
  const globeContRef = useRef<HTMLDivElement>(null);
  const tooltipRef = useRef<HTMLDivElement>(null);

  const geoFeaturesRef = useRef<GeoFeature[]>([]);
  const mapSelRef = useRef<d3.Selection<SVGPathElement, GeoFeature, SVGGElement, unknown> | null>(null);
  const projectionRef = useRef<d3.GeoProjection | null>(null);
  const pathGenRef = useRef<d3.GeoPath | null>(null);
  const globeRef = useRef<InstanceType<typeof Globe> | null>(null);
  const globeInitRef = useRef(false);
  const hoveredGlobeRef = useRef<GeoFeature | null>(null);
  const resizeObsRef = useRef<ResizeObserver | null>(null);
  const zoomRef = useRef<d3.ZoomBehavior<SVGSVGElement, unknown> | null>(null);
  const selectedIsoForD3Ref = useRef<string | null>(null);

  // ── GeoJSON load ────────────────────────────────────────────────────────
  useEffect(() => {
    let cancelled = false;
    async function load() {
      let features: GeoFeature[] = [];
      for (const url of [GEO_URL_PRIMARY, GEO_URL_FALLBACK]) {
        try {
          const res = await fetch(url);
          if (!res.ok) continue;
          const json = await res.json();
          features = json.features as GeoFeature[];
          break;
        } catch { /* try next */ }
      }
      if (cancelled) return;
      geoFeaturesRef.current = features;
      if (features.length) {
        if (view === 'map') initMap(features);
        else initGlobe();
      }
    }
    load();
    return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // ── D3 Map init ─────────────────────────────────────────────────────────
  function initMap(features: GeoFeature[]) {
    const svgEl = mapSvgRef.current;
    const vizEl = mapViewRef.current;
    if (!svgEl || !vizEl) return;
    const W = vizEl.clientWidth;
    const H = vizEl.clientHeight;

    const proj = d3.geoNaturalEarth1().scale(W / 5.8).translate([W / 2, H / 2]);
    projectionRef.current = proj;
    const path = d3.geoPath().projection(proj);
    pathGenRef.current = path;

    const svg = d3.select(svgEl);
    svg.select('#graticule-g').selectAll('*').remove();
    svg.select('#map-g').selectAll('*').remove();

    const grat = d3.geoGraticule()();
    svg.select<SVGGElement>('#graticule-g')
      .append('path')
      .datum(grat)
      .attr('d', path as unknown as string)
      .attr('fill', 'none')
      .attr('stroke', 'rgba(255,255,255,0.04)')
      .attr('stroke-width', 0.4);

    const zoom = d3.zoom<SVGSVGElement, unknown>()
      .scaleExtent([0.5, 10])
      .on('zoom', (e) => {
        d3.select(svgEl).select<SVGGElement>('#map-g').attr('transform', e.transform);
      });
    zoomRef.current = zoom;
    svg.call(zoom);

    const mapSel = svg.select<SVGGElement>('#map-g')
      .selectAll<SVGPathElement, GeoFeature>('path')
      .data(features)
      .join('path')
      .attr('d', (d) => path(d) ?? '')
      .attr('fill', (d) => getCountryFill(readIso3(d.properties)))
      .attr('stroke', 'rgba(255,255,255,0.1)')
      .attr('stroke-width', 0.4)
      .style('cursor', 'pointer')
      .on('mouseenter', function (e: MouseEvent, d: GeoFeature) {
        const iso = readIso3(d.properties);
        const st = getStatus(iso);
        d3.select(this)
          .attr('stroke', '#FF7819')
          .attr('stroke-width', 1.2)
          .attr('fill', brighten(getCountryFill(iso)));
        const tip = tooltipRef.current;
        if (tip) {
          const tn = tip.querySelector<HTMLDivElement>('.tn');
          const ts = tip.querySelector<HTMLDivElement>('.ts');
          if (tn) tn.textContent = (d.properties.ADMIN as string | undefined) ?? (d.properties.admin as string | undefined) ?? iso;
          if (ts) ts.textContent = statusLabels[st] || 'Inactive';
          tip.style.opacity = '1';
          moveTip(e);
        }
      })
      .on('mousemove', function (_e: MouseEvent) { moveTip(_e); })
      .on('mouseleave', function (_e: MouseEvent, d: GeoFeature) {
        const iso = readIso3(d.properties);
        const isSelected = iso === selectedIsoForD3Ref.current;
        d3.select(this)
          .attr('stroke', isSelected ? '#FF7819' : 'rgba(255,255,255,0.1)')
          .attr('stroke-width', isSelected ? 1.5 : 0.4)
          .attr('fill', getCountryFill(iso));
        const tip = tooltipRef.current;
        if (tip) tip.style.opacity = '0';
      })
      .on('click', function (e: MouseEvent, d: GeoFeature) {
        e.stopPropagation();
        const iso = readIso3(d.properties);
        selectCountry(iso);
        mapSelRef.current?.attr('stroke', 'rgba(255,255,255,0.1)').attr('stroke-width', 0.4);
        d3.select(this).attr('stroke', '#FF7819').attr('stroke-width', 1.5);
      });

    mapSelRef.current = mapSel as unknown as d3.Selection<SVGPathElement, GeoFeature, SVGGElement, unknown>;

    svg.on('click', (e: MouseEvent) => {
      const target = e.target as Element;
      if (target.id === 'map-svg' || target.id === 'ocean-bg') handleDeselect();
    });
  }

  function moveTip(e: MouseEvent) {
    const tip = tooltipRef.current;
    const viz = vizRef.current;
    if (!tip || !viz) return;
    const r = viz.getBoundingClientRect();
    tip.style.left = `${e.clientX - r.left + 14}px`;
    tip.style.top = `${e.clientY - r.top - 12}px`;
  }

  function zoomToCountry(feat: GeoFeature) {
    const svgEl = mapSvgRef.current;
    const vizEl = mapViewRef.current;
    const path = pathGenRef.current;
    const zoom = zoomRef.current;
    if (!svgEl || !vizEl || !path || !zoom) return;
    const W = vizEl.clientWidth, H = vizEl.clientHeight;
    const bounds = path.bounds(feat);
    const [[x0, y0], [x1, y1]] = bounds;
    const dx = x1 - x0, dy = y1 - y0;
    const cx = (x0 + x1) / 2, cy = (y0 + y1) / 2;
    const scale = Math.max(1, Math.min(8, 0.7 / Math.max(dx / W, dy / H)));
    const tx = W / 2 - scale * cx, ty = H / 2 - scale * cy;
    d3.select(svgEl)
      .transition().duration(700)
      .call(zoom.transform, d3.zoomIdentity.translate(tx, ty).scale(scale));
  }

  // ── Globe init ──────────────────────────────────────────────────────────
  function initGlobe() {
    if (globeInitRef.current || !globeContRef.current) return;
    const features = geoFeaturesRef.current;
    if (!features.length) return;
    globeInitRef.current = true;

    const el = globeContRef.current;
    const w = el.clientWidth || el.getBoundingClientRect().width || 800;
    const h = el.clientHeight || el.getBoundingClientRect().height || 600;

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
      .polygonCapColor(((feat: object) => getCountryFill(readIso3((feat as GeoFeature).properties))) as never)
      .polygonSideColor((() => 'rgba(0,0,0,0.3)') as never)
      .polygonStrokeColor((() => 'rgba(255,255,255,0.15)') as never)
      .polygonAltitude(((feat: object) => feat === hoveredGlobeRef.current ? 0.025 : 0.008) as never)
      .polygonLabel(((feat: object) => {
        const f = feat as GeoFeature;
        const iso = readIso3(f.properties);
        const st = getStatus(iso);
        const admin = (f.properties.ADMIN as string | undefined) ?? (f.properties.admin as string | undefined) ?? iso;
        return `<div style="background:#1C1C1C;color:#fff;padding:5px 12px;border-radius:999px;font-size:12px;font-family:Inter,sans-serif;font-weight:500">${admin} · ${statusLabels[st]}</div>`;
      }) as never)
      .onPolygonHover(((feat: object | null) => {
        hoveredGlobeRef.current = (feat as GeoFeature | null) || null;
        globe.polygonAltitude(((f: object) => f === hoveredGlobeRef.current ? 0.025 : 0.008) as never);
        if (el) el.style.cursor = feat ? 'pointer' : 'default';
      }) as never)
      .onPolygonClick(((feat: object) => {
        if (!feat) return;
        const f = feat as GeoFeature;
        const iso = readIso3(f.properties);
        selectCountry(iso);
        if (f.bbox) {
          const lat = (f.bbox[1] + f.bbox[3]) / 2;
          const lng = (f.bbox[0] + f.bbox[2]) / 2;
          globe.pointOfView({ lat, lng, altitude: 1.4 }, 700);
        }
      }) as never)
      .pointOfView({ lat: 48, lng: 10, altitude: 1.8 });

    globeRef.current = globe;

    const controls = (globe as unknown as { controls?: () => { autoRotate: boolean; autoRotateSpeed: number } }).controls?.();
    if (controls) { controls.autoRotate = true; controls.autoRotateSpeed = 0.35; }

    const ro = new ResizeObserver(() => {
      const nw = el.clientWidth, nh = el.clientHeight;
      if (nw > 0 && nh > 0) globe.width(nw).height(nh);
    });
    ro.observe(el);
    resizeObsRef.current = ro;

    const rendererObj = (globeRef.current as unknown as { renderer?: () => { domElement: HTMLCanvasElement } }).renderer?.();
    const canvas = rendererObj?.domElement;
    if (canvas) {
      canvas.addEventListener('webglcontextlost', (e) => { e.preventDefault(); }, false);
      canvas.addEventListener('webglcontextrestored', () => {
        globeInitRef.current = false;
        disposeGlobe(globeRef.current);
        globeRef.current = null;
        initGlobe();
      }, false);
    }
  }

  // ── View switch ─────────────────────────────────────────────────────────
  function handleViewSwitch(v: 'map' | 'globe') {
    if (v === view) return;
    setView(v);
    if (v === 'globe') setTimeout(initGlobe, 50);
    else if (v === 'map' && geoFeaturesRef.current.length && !mapSelRef.current) {
      setTimeout(() => initMap(geoFeaturesRef.current), 50);
    }
  }

  // ── Country selection ───────────────────────────────────────────────────
  const selectCountry = useCallback((iso: string) => {
    setSelectedIso(iso);
    selectedIsoForD3Ref.current = iso;
  }, []);

  const handleDeselect = useCallback(() => {
    setSelectedIso(null);
    selectedIsoForD3Ref.current = null;
    mapSelRef.current?.attr('stroke', 'rgba(255,255,255,0.1)').attr('stroke-width', 0.4);
  }, []);

  useEffect(() => {
    if (!mapSelRef.current) return;
    mapSelRef.current
      .attr('stroke', (d: GeoFeature) => readIso3(d.properties) === selectedIso ? '#FF7819' : 'rgba(255,255,255,0.1)')
      .attr('stroke-width', (d: GeoFeature) => readIso3(d.properties) === selectedIso ? 1.5 : 0.4);
  }, [selectedIso]);

  // ── Search ──────────────────────────────────────────────────────────────
  function handleSearchChange(e: React.ChangeEvent<HTMLInputElement>) {
    const q = e.target.value;
    setSearchQuery(q);
    const trimmed = q.toLowerCase().trim();
    if (!trimmed || trimmed.length < 2) { setSearchOpen(false); setSearchMatches([]); return; }
    const matches = geoFeaturesRef.current
      .filter(f => {
        const name = ((f.properties.ADMIN as string | undefined) ?? (f.properties.admin as string | undefined) ?? '').toLowerCase();
        return name.includes(trimmed);
      })
      .slice(0, 8);
    setSearchMatches(matches);
    setSearchOpen(matches.length > 0);
  }

  function handleSearchSelect(feat: GeoFeature) {
    const iso = readIso3(feat.properties);
    setSearchQuery('');
    setSearchOpen(false);
    setSearchMatches([]);
    selectCountry(iso);
    if (view === 'map' && mapSelRef.current && pathGenRef.current) {
      zoomToCountry(feat);
      mapSelRef.current.attr('stroke', 'rgba(255,255,255,0.1)').attr('stroke-width', 0.4);
      mapSelRef.current
        .filter((d: GeoFeature) => readIso3(d.properties) === iso)
        .attr('stroke', '#FF7819').attr('stroke-width', 1.5);
    } else if (view === 'globe' && globeRef.current) {
      if (feat.bbox) {
        const lat = (feat.bbox[1] + feat.bbox[3]) / 2;
        const lng = (feat.bbox[0] + feat.bbox[2]) / 2;
        (globeRef.current as unknown as { pointOfView: (p: object, ms: number) => void })
          .pointOfView({ lat, lng, altitude: 1.2 }, 1000);
      }
    }
  }

  // ── Init globe on first mount (default view = globe) ───────────────────
  useEffect(() => {
    const timer = setTimeout(() => {
      if (geoFeaturesRef.current.length) initGlobe();
    }, 100);
    return () => clearTimeout(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    return () => {
      resizeObsRef.current?.disconnect();
      resizeObsRef.current = null;
      disposeGlobe(globeRef.current);
      globeRef.current = null;
      globeInitRef.current = false;
    };
  }, []);

  // ── Derived panel data ──────────────────────────────────────────────────
  const status = selectedIso ? getStatus(selectedIso) : null;
  const detail = selectedIso ? (countryDetails[selectedIso] ?? null) : null;
  const countryName = detail?.name || selectedIso || '';
  const pill = status ? PILL_STYLE[status] : null;
  const statusText = status ? statusLabels[status] : '';
  const relatedDocs = selectedIso && status
    ? documents.filter(d => STATUS_DOCS_KEYS[status].includes(d.category))
    : [];

  // ── Render ──────────────────────────────────────────────────────────────
  return (
    <div className="flex flex-col" style={{ minHeight: '100vh', color: '#E8E8E8' }}>

      {/* Header */}
      <div className="px-6 py-10 max-w-6xl mx-auto w-full">
        <div className="flex items-center justify-between mb-6">
          <div>
            <h1 className="text-2xl font-semibold text-white mb-1">Jurisdictions</h1>
            <p className="font-mono text-[11px] uppercase tracking-wider" style={{ color: '#6B6B6B' }}>
              Global compliance coverage map
            </p>
          </div>
          <Link
            to="/launches"
            className="px-4 py-2 rounded-xl text-[13px] font-medium transition-all"
            style={{
              background: 'rgba(255,120,25,0.14)',
              border: '1px solid rgba(255,120,25,0.35)',
              color: '#FF9F55',
            }}
          >
            ← Launches
          </Link>
        </div>
      </div>

      {/* Main panel: globe + sidebar */}
      <div
        className="flex-1 flex overflow-hidden mx-6 mb-6 max-w-6xl mx-auto w-full rounded-xl"
        style={{
          background: '#0D0D0D',
          border: '1px solid rgba(255,255,255,0.06)',
          minHeight: 640,
          maxWidth: '72rem',
        }}
      >

        {/* Toolbar strip */}
        <div className="flex flex-col flex-1 min-w-0 overflow-hidden">
          <div
            className="flex items-center gap-4 px-5 shrink-0"
            style={{
              height: 44,
              borderBottom: '1px solid rgba(255,255,255,0.06)',
            }}
          >
            <div className="flex gap-[2px] p-[3px] rounded-full" style={{ background: 'rgba(255,255,255,0.05)' }}>
              {(['globe', 'map'] as const).map((v) => (
                <button
                  key={v}
                  onClick={() => handleViewSwitch(v)}
                  className="rounded-full px-[22px] py-[7px] text-[13.5px] font-semibold transition-all duration-[220ms] cursor-pointer border-0"
                  style={{
                    background: view === v ? '#FF7819' : 'transparent',
                    color: view === v ? '#fff' : 'rgba(255,255,255,0.4)',
                  }}
                >
                  {v === 'map' ? 'Map' : 'Globe'}
                </button>
              ))}
            </div>

            <div className="relative ml-auto">
              <span className="absolute left-[13px] top-1/2 -translate-y-1/2 pointer-events-none" style={{ color: 'rgba(255,255,255,0.3)' }}>
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                  <circle cx="11" cy="11" r="8" />
                  <line x1="21" y1="21" x2="16.65" y2="16.65" />
                </svg>
              </span>
              <input
                value={searchQuery}
                onChange={handleSearchChange}
                onBlur={() => setTimeout(() => setSearchOpen(false), 200)}
                onFocus={() => searchMatches.length > 0 && setSearchOpen(true)}
                placeholder="Search country…"
                autoComplete="off"
                className="rounded-full text-[13px] text-white outline-none w-56 pl-[38px] pr-4 py-2"
                style={{
                  background: 'rgba(255,255,255,0.05)',
                  border: '1px solid rgba(255,255,255,0.08)',
                }}
                onFocusCapture={(e) => { (e.target as HTMLInputElement).style.borderColor = 'rgba(255,120,25,0.4)'; }}
                onBlurCapture={(e) => { (e.target as HTMLInputElement).style.borderColor = 'rgba(255,255,255,0.08)'; }}
              />
              {searchOpen && (
                <div
                  className="absolute top-[calc(100%+6px)] left-0 right-0 z-50 overflow-hidden rounded-xl"
                  style={{ background: '#161616', border: '1px solid rgba(255,255,255,0.08)' }}
                >
                  {searchMatches.map((feat) => {
                    const iso = readIso3(feat.properties);
                    const st = getStatus(iso);
                    const p = PILL_STYLE[st];
                    return (
                      <div
                        key={iso}
                        className="flex items-center justify-between px-[14px] py-[9px] text-[13px] cursor-pointer transition-colors duration-150 hover:bg-white/[0.04]"
                        onMouseDown={() => handleSearchSelect(feat)}
                      >
                        <span style={{ color: 'rgba(255,255,255,0.85)' }}>
                          {(feat.properties.ADMIN as string | undefined) ?? (feat.properties.admin as string | undefined) ?? iso}
                        </span>
                        <span className="text-[11px] rounded-full px-2 py-[2px]" style={{ background: p.bg, color: p.color }}>
                          {statusLabels[st]}
                        </span>
                      </div>
                    );
                  })}
                </div>
              )}
            </div>
          </div>

          {/* Visualization area */}
          <div ref={vizRef} className="flex-1 relative overflow-hidden">

            {/* D3 Map */}
            <div
              ref={mapViewRef}
              className="absolute inset-0 transition-opacity duration-300"
              style={{ opacity: view === 'map' ? 1 : 0, pointerEvents: view === 'map' ? 'auto' : 'none' }}
            >
              <svg ref={mapSvgRef} id="map-svg" className="w-full h-full" style={{ cursor: 'grab' }}>
                <defs>
                  <radialGradient id="ocean-grad" cx="50%" cy="50%" r="50%">
                    <stop offset="0%" stopColor="#0A0A14" />
                    <stop offset="100%" stopColor="#050508" />
                  </radialGradient>
                </defs>
                <rect id="ocean-bg" width="100%" height="100%" fill="url(#ocean-grad)" />
                <g id="graticule-g" />
                <g id="map-g" />
              </svg>
            </div>

            {/* Globe */}
            <div
              ref={globeViewRef}
              className="absolute inset-0 transition-opacity duration-300"
              style={{ opacity: view === 'globe' ? 1 : 0, pointerEvents: view === 'globe' ? 'auto' : 'none' }}
            >
              <div ref={globeContRef} className="w-full h-full" />
            </div>

            {/* Tooltip */}
            <div
              ref={tooltipRef}
              className="absolute pointer-events-none z-50 transition-opacity duration-100"
              style={{
                background: 'rgba(13,13,13,0.95)',
                border: '1px solid rgba(255,255,255,0.08)',
                borderRadius: 8,
                padding: '7px 12px',
                fontSize: 12,
                color: '#fff',
                opacity: 0,
              }}
            >
              <div className="tn font-semibold mb-[2px]" />
              <div className="ts text-[11px] opacity-50" />
            </div>
          </div>
        </div>

        {/* Right sidebar */}
        <div
          className="flex flex-col overflow-hidden shrink-0"
          style={{ width: 300, minWidth: 300, borderLeft: '1px solid rgba(255,255,255,0.05)' }}
        >
          <div
            className="px-[22px] pt-[22px] pb-[18px] shrink-0"
            style={{ borderBottom: '1px solid rgba(255,255,255,0.05)' }}
          >
            <div
              className="text-[11px] font-bold uppercase tracking-[0.1em]"
              style={{ color: 'rgba(255,255,255,0.3)' }}
            >
              {selectedIso ? 'Country' : 'Jurisdiction Overview'}
            </div>
          </div>

          <div
            className="flex-1 overflow-y-auto px-[22px] py-[20px]"
            style={{ scrollbarWidth: 'thin', scrollbarColor: '#222 transparent' }}
          >
            {!selectedIso ? (
              <>
                {/* Stats grid */}
                <div className="grid grid-cols-2 gap-[10px] mb-5">
                  {[
                    { n: stats.activeEU, label: 'EU Active', color: '#FF7819' },
                    { n: stats.watchlist, label: 'Watchlist', color: '#C47A2A' },
                    { n: stats.restricted, label: 'Restricted', color: '#E05050' },
                    { n: stats.eea, label: 'EEA/Other', color: '#FF7819' },
                  ].map(({ n, label, color }) => (
                    <div
                      key={label}
                      className="rounded-xl p-4"
                      style={{ background: '#141414', border: '1px solid rgba(255,255,255,0.06)' }}
                    >
                      <div className="text-[26px] font-bold tracking-[-0.03em]" style={{ color }}>{n}</div>
                      <div className="text-[11px] mt-[3px]" style={{ color: 'rgba(255,255,255,0.4)' }}>{label}</div>
                    </div>
                  ))}
                </div>

                {/* Legend */}
                <div className="mb-5">
                  <div className="text-[10px] font-bold uppercase tracking-[0.08em] mb-[10px]" style={{ color: 'rgba(255,255,255,0.3)' }}>
                    Status
                  </div>
                  {[
                    { color: '#FF7819', label: 'Active — bunq operates' },
                    { color: '#C47A2A', label: 'Watchlist — enhanced monitoring' },
                    { color: '#7A1A1A', label: 'Restricted — sanctioned' },
                    { color: '#333', label: 'Inactive — no operations', border: '1px solid #444' },
                  ].map(({ color, label, border }) => (
                    <div key={label} className="flex items-center gap-[10px] mb-2">
                      <div className="w-[10px] h-[10px] rounded-full shrink-0" style={{ background: color, border }} />
                      <div className="text-[13px]" style={{ color: 'rgba(255,255,255,0.6)' }}>{label}</div>
                    </div>
                  ))}
                </div>

                <div
                  className="rounded-xl px-[14px] py-3 text-[12px] leading-[1.5]"
                  style={{
                    background: 'rgba(255,120,25,0.06)',
                    border: '1px solid rgba(255,120,25,0.14)',
                    color: 'rgba(255,255,255,0.5)',
                  }}
                >
                  Click a country on the map or globe to see details and related documents.
                </div>
              </>
            ) : (
              <>
                <button
                  onClick={handleDeselect}
                  className="flex items-center gap-[5px] text-[12px] mb-[18px] cursor-pointer bg-transparent border-0 p-0 transition-colors duration-150"
                  style={{ color: 'rgba(255,255,255,0.3)' }}
                  onMouseEnter={(e) => (e.currentTarget.style.color = 'rgba(255,255,255,0.6)')}
                  onMouseLeave={(e) => (e.currentTarget.style.color = 'rgba(255,255,255,0.3)')}
                >
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                    <polyline points="15 18 9 12 15 6" />
                  </svg>
                  All countries
                </button>

                <div className="mb-[18px]">
                  <div className="text-[26px] font-bold tracking-[-0.025em] text-white leading-[1.15] mb-2">
                    {countryName}
                  </div>
                  {pill && (
                    <span
                      className="inline-block rounded-full text-[12px] font-bold px-[14px] py-[5px]"
                      style={{ background: pill.bg, color: pill.color }}
                    >
                      {statusText}
                    </span>
                  )}
                </div>

                {detail?.license && (
                  <div className="rounded-xl px-[18px] py-4 mb-[14px]" style={{ background: '#141414', border: '1px solid rgba(255,255,255,0.06)' }}>
                    <div className="text-[10px] font-bold uppercase tracking-[0.08em] mb-1" style={{ color: 'rgba(255,255,255,0.3)' }}>License Type</div>
                    <div className="text-[14px] font-medium" style={{ color: 'rgba(255,255,255,0.85)' }}>{detail.license}</div>
                  </div>
                )}

                {detail?.regulator && (
                  <div className="rounded-xl px-[18px] py-4 mb-[14px]" style={{ background: '#141414', border: '1px solid rgba(255,255,255,0.06)' }}>
                    <div className="text-[10px] font-bold uppercase tracking-[0.08em] mb-1" style={{ color: 'rgba(255,255,255,0.3)' }}>Regulator</div>
                    <div className="text-[14px] font-medium" style={{ color: 'rgba(255,255,255,0.85)' }}>{detail.regulator}</div>
                  </div>
                )}

                {detail?.note && (
                  <div className="rounded-xl px-[18px] py-4 mb-[14px]" style={{ background: '#141414', border: '1px solid rgba(255,255,255,0.06)' }}>
                    <div className="text-[10px] font-bold uppercase tracking-[0.08em] mb-1" style={{ color: 'rgba(255,255,255,0.3)' }}>Note</div>
                    <div className="text-[13px]" style={{ color: 'rgba(255,255,255,0.55)' }}>{detail.note}</div>
                  </div>
                )}

                <div className="mt-[6px]">
                  <div className="text-[10px] font-bold uppercase tracking-[0.08em] mb-[10px]" style={{ color: 'rgba(255,255,255,0.3)' }}>
                    Related Documents
                  </div>
                  {(relatedDocs.length > 0 ? relatedDocs : documents.slice(0, 4)).map((doc) => (
                    <div
                      key={doc.id}
                      className="flex items-center gap-[10px] rounded-xl px-[15px] py-[13px] mb-2 cursor-pointer transition-all duration-150"
                      style={{ background: '#141414', border: '1px solid rgba(255,255,255,0.06)' }}
                      onMouseEnter={(e) => {
                        (e.currentTarget as HTMLDivElement).style.background = '#1C1C1C';
                        (e.currentTarget as HTMLDivElement).style.borderColor = 'rgba(255,120,25,0.2)';
                      }}
                      onMouseLeave={(e) => {
                        (e.currentTarget as HTMLDivElement).style.background = '#141414';
                        (e.currentTarget as HTMLDivElement).style.borderColor = 'rgba(255,255,255,0.06)';
                      }}
                    >
                      <div className="w-[6px] h-[6px] rounded-full shrink-0" style={{ background: CAT_COLOR[doc.category] || '#666' }} />
                      <div className="flex-1">
                        <div className="text-[12.5px] font-medium leading-[1.35]" style={{ color: 'rgba(255,255,255,0.82)' }}>{doc.title}</div>
                        <div className="text-[10px] mt-[2px]" style={{ color: 'rgba(255,255,255,0.35)' }}>{doc.category}</div>
                      </div>
                      <div style={{ color: 'rgba(255,120,25,0.5)', fontSize: 14 }}>→</div>
                    </div>
                  ))}
                </div>
              </>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}

