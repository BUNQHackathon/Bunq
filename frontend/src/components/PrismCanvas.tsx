import { useEffect, useState } from 'react';
import { Canvas } from '@react-three/fiber';
import { AdaptiveDpr, PerformanceMonitor } from '@react-three/drei';
import * as THREE from 'three';
import { GlassPrisms } from './GlassPrisms';
import { BackdropMesh } from './BackdropMesh';

export default function PrismCanvas() {
  const [visible, setVisible] = useState(
    typeof document === 'undefined' ? true : !document.hidden,
  );

  useEffect(() => {
    const onVis = () => setVisible(!document.hidden);
    document.addEventListener('visibilitychange', onVis);
    return () => document.removeEventListener('visibilitychange', onVis);
  }, []);

  return (
    <div
      className="absolute inset-0 pointer-events-none overflow-hidden"
      style={{ zIndex: 0 }}
    >
      <Canvas
        camera={{ position: [0, 0, 16.5], fov: 35, near: 0.01, far: 100 }}
        gl={{
          antialias: true,
          toneMapping: THREE.NoToneMapping,
          powerPreference: 'high-performance',
        }}
        dpr={[1, 1.5]}
        frameloop={visible ? 'always' : 'never'}
        performance={{ min: 0.5 }}
        style={{ width: '100%', height: '100%', background: 'transparent' }}
      >
        <PerformanceMonitor />
        <AdaptiveDpr pixelated />
        <BackdropMesh />
        <GlassPrisms />
      </Canvas>
    </div>
  );
}
