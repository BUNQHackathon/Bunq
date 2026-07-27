import { useRef, useEffect, useMemo } from "react";
import { MeshTransmissionMaterial } from "@react-three/drei";
import * as THREE from "three";

const PRISM_COUNT = 14;

const BASE_COLOR = '#e17244';
const EDGE_COLOR = '#3556e8';
const EDGE_STRENGTH = 1.7;

export function GlassPrisms() {
  const ref = useRef<THREE.InstancedMesh>(null!);
  const matRef = useRef<any>(null!);
  const geo = useMemo(() => new THREE.CylinderGeometry(0.5, 0.5, 15, 8), []);

  useEffect(() => {
    if (!ref.current) return;
    const obj = new THREE.Object3D();
    for (let i = 0; i < PRISM_COUNT; i++) {
      obj.position.set(-6.5 + i, 0, 0);
      obj.rotation.set(0, 0, 0);
      obj.scale.setScalar(1);
      obj.updateMatrix();
      ref.current.setMatrixAt(i, obj.matrix);
    }
    ref.current.instanceMatrix.needsUpdate = true;
    ref.current.instanceMatrix.setUsage(THREE.StaticDrawUsage);
  }, [geo]);

  useEffect(() => {
    const mat = matRef.current;
    if (!mat) return;
    const prev = mat.onBeforeCompile;
    const edge = new THREE.Color(EDGE_COLOR);
    mat.onBeforeCompile = (shader: any, renderer: any) => {
      prev(shader, renderer);
      if (shader.fragmentShader.includes('gExcess')) return;
      const before = shader.fragmentShader;
      shader.fragmentShader = shader.fragmentShader.replace(
        '        totalDiffuse = mix( totalDiffuse, transmission.rgb, material.transmission );',
        `        float gExcess = max(transmission.g - max(transmission.r, transmission.b), 0.0);
        transmission.rgb += gExcess * ${EDGE_STRENGTH.toFixed(2)} * (vec3(${edge.r.toFixed(4)}, ${edge.g.toFixed(4)}, ${edge.b.toFixed(4)}) - vec3(0.0, 1.0, 0.0));
        transmission.rgb = max(transmission.rgb, vec3(0.0));
        totalDiffuse = mix( totalDiffuse, transmission.rgb, material.transmission );`
      );
      if (shader.fragmentShader === before) console.warn('[prism-tint] anchor not found');
    };
    mat.needsUpdate = true;
  }, []);

  return (
    <instancedMesh ref={ref} args={[geo, undefined, PRISM_COUNT]} frustumCulled={false}>
      <MeshTransmissionMaterial
        ref={matRef}
        transmission={1}
        thickness={1}
        roughness={0.33}
        ior={1.5}
        chromaticAberration={3}
        anisotropicBlur={2.9}
        distortion={0.1}
        distortionScale={0.18}
        temporalDistortion={0}
        specularIntensity={0}
        color={BASE_COLOR}
        resolution={256}
        samples={2}
      />
    </instancedMesh>
  );
}
