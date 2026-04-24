import { useRef, useEffect, useMemo } from "react";
import { MeshTransmissionMaterial } from "@react-three/drei";
import * as THREE from "three";

const PRISM_COUNT = 14;

const PRISM_TINTS = [
  '#238648', '#238648',
  '#309B47', '#61B650', '#88CC53', '#3DBAAC',
  '#3395D8', '#2973BB', '#1E5C85', '#9A3333',
  '#E22F30', '#F28827',
  '#F5C836', '#F5C836',
];

export function GlassPrisms() {
  const ref = useRef<THREE.InstancedMesh>(null!);
  const geo = useMemo(() => new THREE.CylinderGeometry(0.5, 0.5, 15, 8), []);

  useEffect(() => {
    if (!ref.current) return;
    const obj = new THREE.Object3D();
    const c = new THREE.Color();
    for (let i = 0; i < PRISM_COUNT; i++) {
      obj.position.set(-6.5 + i, 0, 0);
      obj.rotation.set(0, 0, 0);
      obj.scale.setScalar(1);
      obj.updateMatrix();
      ref.current.setMatrixAt(i, obj.matrix);
      ref.current.setColorAt(i, c.set(PRISM_TINTS[i]));
    }
    ref.current.instanceMatrix.needsUpdate = true;
    ref.current.instanceColor!.needsUpdate = true;
  }, [geo]);

  return (
    <instancedMesh ref={ref} args={[geo, undefined, PRISM_COUNT]} frustumCulled={false}>
      <MeshTransmissionMaterial
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
        color="white"
        resolution={512}
        samples={4}
      />
    </instancedMesh>
  );
}
