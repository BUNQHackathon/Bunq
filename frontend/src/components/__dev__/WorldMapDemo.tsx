/**
 * Dev-only smoke test. Not routed in production.
 * Mounts WorldMapD3 + WorldMapGlobe with a hardcoded 6-country dataset.
 */
import { useState } from 'react';
import WorldMapD3 from '../WorldMapD3';
import WorldMapGlobe from '../WorldMapGlobe';

const DEMO_DATA = new Map([
  ['NLD', { color: '#FF7819', label: 'Netherlands' }],
  ['DEU', { color: '#FF7819', label: 'Germany' }],
  ['FRA', { color: '#FF7819', label: 'France' }],
  ['GBR', { color: '#9B5E1A', label: 'United Kingdom' }],
  ['USA', { color: '#9B5E1A', label: 'United States' }],
  ['IRL', { color: '#FF7819', label: 'Ireland' }],
]);

export default function WorldMapDemo() {
  const [selected, setSelected] = useState<string | undefined>(undefined);

  return (
    <div style={{ background: '#080808', color: '#fff', padding: 24, minHeight: '100vh' }}>
      <h2 style={{ marginBottom: 8 }}>WorldMap Dev Demo — selected: {selected ?? '(none)'}</h2>
      <h3 style={{ marginBottom: 4 }}>D3 Flat Map</h3>
      <WorldMapD3 data={DEMO_DATA} selected={selected} onSelect={setSelected} height={380} />
      <h3 style={{ margin: '24px 0 4px' }}>Globe</h3>
      <WorldMapGlobe data={DEMO_DATA} selected={selected} onSelect={setSelected} height={380} />
    </div>
  );
}
