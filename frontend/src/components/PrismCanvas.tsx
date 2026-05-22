import { useEffect, useRef, useState } from 'react';
import { Canvas, useFrame } from '@react-three/fiber';
import { AdaptiveDpr } from '@react-three/drei';
import * as THREE from 'three';
import { GlassPrisms } from './GlassPrisms';
import { BackdropMesh } from './BackdropMesh';

interface SceneFadeControllerProps {
  phase: 'visible' | 'fadingOut' | 'hidden';
  groupRef: React.RefObject<THREE.Group>;
  onComplete: () => void;
}

function SceneFadeController({ phase, groupRef, onComplete }: SceneFadeControllerProps) {
  const startTime = useRef<number | null>(null);
  const completedRef = useRef(false);

  useEffect(() => {
    if (phase === 'fadingOut') {
      startTime.current = performance.now();
      completedRef.current = false;
    } else if (phase === 'visible') {
      startTime.current = null;
      completedRef.current = false;
    }
  }, [phase]);

  useFrame(() => {
    if (phase !== 'fadingOut' || startTime.current === null || !groupRef.current) return;
    const elapsed = (performance.now() - startTime.current) / 1000;
    const cubeProgress = Math.min(elapsed / 0.6, 1);
    const eased = 1 - Math.pow(1 - cubeProgress, 3);
    groupRef.current.scale.setScalar(1 - eased);
    if (elapsed >= 0.8 && !completedRef.current) {
      completedRef.current = true;
      onComplete();
    }
  });

  return null;
}

interface PrismCanvasProps {
  phase: 'visible' | 'fadingOut' | 'hidden';
  onFadeOutComplete: () => void;
}

export default function PrismCanvas({ phase, onFadeOutComplete }: PrismCanvasProps) {
  const [visible, setVisible] = useState(
    typeof document === 'undefined' ? true : !document.hidden,
  );
  const groupRef = useRef<THREE.Group>(null!);

  useEffect(() => {
    const onVis = () => setVisible(!document.hidden);
    document.addEventListener('visibilitychange', onVis);
    return () => document.removeEventListener('visibilitychange', onVis);
  }, []);

  if (phase === 'hidden') return null;

  return (
    <div
      className="absolute inset-0 pointer-events-none overflow-hidden bg-[#000]"
      style={{
        zIndex: 0,
        opacity: phase === 'fadingOut' ? 0 : 1,
        transition: phase === 'fadingOut' ? 'opacity 600ms cubic-bezier(0.22, 1, 0.36, 1) 200ms' : 'none',
      }}
    >
      <Canvas
        camera={{ position: [0, 0, 16.5], fov: 35, near: 0.01, far: 100 }}
        gl={{
          antialias: true,
          toneMapping: THREE.NoToneMapping,
          powerPreference: 'high-performance',
        }}
        dpr={[1, 1.25]}
        frameloop={visible ? 'always' : 'never'}
        performance={{ min: 0.5 }}
        style={{ width: '100%', height: '100%', background: 'transparent' }}
      >
        <AdaptiveDpr pixelated />
        <group ref={groupRef} scale={1}>
          <BackdropMesh />
        </group>
        <GlassPrisms />
        <SceneFadeController phase={phase} groupRef={groupRef} onComplete={onFadeOutComplete} />
      </Canvas>
    </div>
  );
}
